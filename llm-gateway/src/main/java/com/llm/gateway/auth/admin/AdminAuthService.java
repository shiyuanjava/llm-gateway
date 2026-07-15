package com.llm.gateway.auth.admin;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.llm.gateway.audit.AdminAuditService;
import com.llm.gateway.config.AdminAuthProperties;
import com.llm.gateway.exception.AuthenticationException;
import com.llm.gateway.persistence.entity.AdminUserEntity;
import com.llm.gateway.persistence.mapper.AdminUserMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 管理端账号鉴权服务：BCrypt 密码校验、JWT（HS256）签发与验签、登录防爆破锁定、
 * 首个管理员账号引导。
 *
 * <p>密钥轮换：签发恒用当前密钥；验签依次尝试「当前 + 历史」密钥列表
 * （{@code gateway.admin.jwt-secrets-fallback}），轮换时旧 token 在 TTL 内仍有效，平滑过渡。
 *
 * <p>不引入 Spring Security 全家桶，与网关现有手写 Filter 风格一致。
 */
@Service
public class AdminAuthService {

    /** 登录锁定异常：连续失败次数达到上限。 */
    public static class LoginLockedException extends RuntimeException {
        public LoginLockedException(String message) {
            super(message);
        }
    }

    /** 登录结果:JWT 与过期时刻(epoch 毫秒,与 token 的 exp 一致)。 */
    public record LoginResult(String token, long expiresAtMillis) {}

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final int MIN_SECRET_LENGTH = 32;
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 5 * 60_000L;

    private final AdminUserMapper adminUserMapper;
    private final AdminAuthProperties properties;

    @Nullable
    private final AdminAuditService auditService;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecretKey signingKey;
    /** 验签密钥链：当前签发密钥在前，历史密钥在后（轮换过渡期）。 */
    private final List<SecretKey> verifyKeys;
    /**
     * username -> 失败状态（次数 + 锁定截止时间）。Caffeine 限容 + 过期，
     * 用户名爆破探测不会撑爆内存。单实例内存即可，多实例部署由 SCA 阶段统一。
     */
    private final Cache<String, FailureState> failures = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMillis(LOCK_MILLIS * 3))
            .build();

    private record FailureState(int count, long lockedUntilMs) {}

    public AdminAuthService(
            AdminUserMapper adminUserMapper, AdminAuthProperties properties, @Nullable AdminAuditService auditService) {
        if (properties.jwtSecret() == null || properties.jwtSecret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("GATEWAY_JWT_SECRET 未配置或长度不足 " + MIN_SECRET_LENGTH + " 字符，拒绝启动");
        }
        this.adminUserMapper = adminUserMapper;
        this.properties = properties;
        this.auditService = auditService;
        this.signingKey = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        List<SecretKey> keys = new ArrayList<>();
        keys.add(signingKey);
        if (properties.jwtSecretsFallback() != null) {
            for (String secret : properties.jwtSecretsFallback()) {
                if (secret != null && secret.length() >= MIN_SECRET_LENGTH) {
                    keys.add(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        this.verifyKeys = List.copyOf(keys);
        bootstrapAdmin();
    }

    /**
     * 登录：校验用户名密码，通过则签发 JWT。
     *
     * @param username 用户名
     * @param password 明文密码
     * @param clientIp 来源 IP（审计用）
     * @return JWT 与过期时刻
     * @throws AuthenticationException 用户名或密码错误（不区分，防枚举）
     * @throws LoginLockedException    连续失败被锁定
     */
    public LoginResult login(String username, String password, String clientIp) {
        FailureState state = failures.getIfPresent(username);
        long now = System.currentTimeMillis();
        if (state != null && state.lockedUntilMs() > now) {
            audit(username, "LOGIN_LOCKED", clientIp, 423);
            throw new LoginLockedException("登录失败次数过多，请 5 分钟后再试");
        }
        AdminUserEntity user = adminUserMapper.selectOne(
                Wrappers.<AdminUserEntity>lambdaQuery().eq(AdminUserEntity::getUsername, username));
        boolean ok = user != null
                && !Boolean.FALSE.equals(user.getEnabled())
                && encoder.matches(password, user.getPasswordHash());
        if (!ok) {
            recordFailure(username, now);
            audit(username, "LOGIN_FAIL", clientIp, 401);
            throw new AuthenticationException("用户名或密码错误");
        }
        failures.invalidate(username);
        audit(username, "LOGIN_OK", clientIp, 200);
        Date expiry = new Date(now + properties.tokenTtlMinutes() * 60_000L);
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now))
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new LoginResult(token, expiry.getTime());
    }

    /**
     * 验签：合法且未过期则返回主体。依次尝试当前与历史密钥（密钥轮换过渡期）。
     *
     * @param token JWT
     * @return 主体，非法/过期为空
     */
    public Optional<AdminPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        for (SecretKey key : verifyKeys) {
            try {
                String username = Jwts.parser()
                        .verifyWith(key)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload()
                        .getSubject();
                return Optional.of(new AdminPrincipal(username));
            } catch (Exception e) {
                // 换下一把密钥再试；全部失败视为非法 token
            }
        }
        return Optional.empty();
    }

    /** 引导：admin_user 表为空且配置了引导账号时创建首个管理员。 */
    private void bootstrapAdmin() {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        String username = properties.bootstrapUsername();
        String password = properties.bootstrapPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("admin_user 表为空且未配置 ADMIN_USERNAME/ADMIN_PASSWORD，管理端将无法登录");
            return;
        }
        AdminUserEntity user = new AdminUserEntity();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setEnabled(true);
        adminUserMapper.insert(user);
        log.info("已创建引导管理员账号 [{}]", username);
    }

    /** 记录一次失败，达到上限则设置锁定截止时间。 */
    private void recordFailure(String username, long now) {
        failures.asMap().compute(username, (u, old) -> {
            int count = (old == null || old.lockedUntilMs() > 0 ? 0 : old.count()) + 1;
            long lockedUntil = count >= MAX_FAILURES ? now + LOCK_MILLIS : 0;
            return new FailureState(count, lockedUntil);
        });
    }

    /** 审计（服务未装配时跳过——单元测试场景）。 */
    private void audit(String username, String action, String clientIp, int status) {
        if (auditService != null) {
            auditService.record(username, action, "auth/login", null, clientIp, status);
        }
    }
}
