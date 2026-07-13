# LLM Gateway 安全基线 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** /admin/** JWT 账号密码鉴权、API Key SHA-256 哈希存储(生成/一次性展示/迁移)、管理面审计日志。

**Architecture:** 手写 Filter 风格(与现有 ApiKeyAuthFilter 一致,不引入 Spring Security 全家桶):AdminJwtFilter 验签 → AdminAuditFilter 记审计 → 现有 Controller。ApiKeyService 内存索引改为以 SHA-256 哈希为 key。存量明文 Key 由启动 Runner 迁移后删除明文列。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + MySQL;jjwt 0.12.x + spring-security-crypto(BCrypt);Vue 3 + Element Plus。

**环境说明:**
- 两个项目都不是 git 仓库,所有 Commit 步骤替换为编译/测试验证。
- Maven:`cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test`(mvnw 不可用)。
- 前端:`cd C:/practice/llm-gateway-ui && npm run build`。
- 后端统一响应包装:`R<T>{code,msg,data}`(`R.ok(...)`);分页:`PageResult<T>(records,total,current,size)`;配置类通过 `@ConfigurationPropertiesScan` 自动注册(应用类已开启)。

---

## Task 1: 依赖 + 管理端配置项

**Files:**
- Modify: `llm-gateway/pom.xml`
- Create: `llm-gateway/src/main/java/com/llm/gateway/config/AdminAuthProperties.java`
- Modify: `llm-gateway/src/main/resources/application.yaml`
- Modify: `llm-gateway/src/test/java/com/llm/gateway/LlmGatewayApplicationTests.java`

- [ ] **Step 1: pom.xml 加依赖**(加在 mysql-connector-j 之后)

```xml
        <!-- 管理端 JWT 鉴权 -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <!-- 仅用 BCrypt,不引入 Spring Security 全家桶 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
        </dependency>
```

注:`spring-security-crypto` 版本由 Spring Boot BOM 管理,无需写版本号。若 jjwt-jackson 与项目的 Jackson 3(`tools.jackson`)冲突导致运行时序列化报错,改用 `jjwt-gson`(0.12.6)+ 排除说明,并在报告中注明。

- [ ] **Step 2: 新建 AdminAuthProperties**

```java
package com.llm.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端鉴权配置(前缀 {@code gateway.admin})。
 *
 * @param jwtSecret         JWT HS256 密钥,生产必须 ≥32 字符(由 GATEWAY_JWT_SECRET 注入)
 * @param tokenTtlMinutes   JWT 有效期(分钟)
 * @param bootstrapUsername 首个管理员用户名(仅 admin_user 表为空时生效)
 * @param bootstrapPassword 首个管理员密码(仅 admin_user 表为空时生效)
 */
@ConfigurationProperties(prefix = "gateway.admin")
public record AdminAuthProperties(
        String jwtSecret,
        long tokenTtlMinutes,
        String bootstrapUsername,
        String bootstrapPassword) {
}
```

- [ ] **Step 3: application.yaml 的 gateway 段追加**(在 `http:` 之后)

```yaml
  # ---- 管理端鉴权 ----
  admin:
    jwt-secret: ${GATEWAY_JWT_SECRET:}
    token-ttl-minutes: 120
    bootstrap-username: ${ADMIN_USERNAME:}
    bootstrap-password: ${ADMIN_PASSWORD:}
```

- [ ] **Step 4: 上下文测试补测试密钥**

`LlmGatewayApplicationTests` 的 `@SpringBootTest` 注解改为:

```java
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=admin",
        "gateway.admin.bootstrap-password=admin123"
})
```

(保留注解上原有属性,若已有 properties 则合并。)

- [ ] **Step 5: 编译验证**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q compile`
Expected: BUILD SUCCESS。

---

## Task 2: admin_user / admin_audit_log 表与实体

**Files:**
- Modify: `llm-gateway/src/main/resources/schema.sql`
- Create: `llm-gateway/src/main/java/com/llm/gateway/persistence/entity/AdminUserEntity.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/persistence/entity/AdminAuditLogEntity.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/persistence/mapper/AdminUserMapper.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/persistence/mapper/AdminAuditLogMapper.java`

- [ ] **Step 1: schema.sql 末尾追加两张表**

```sql
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    username      VARCHAR(64)  NOT NULL                COMMENT '管理员用户名',
    password_hash VARCHAR(100) NOT NULL                COMMENT 'BCrypt 密码哈希',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用，0 停用',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理员账号';

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    username   VARCHAR(64)  NOT NULL                COMMENT '操作者用户名（登录失败时为尝试的用户名）',
    action     VARCHAR(32)  NOT NULL                COMMENT 'LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD',
    resource   VARCHAR(255) NOT NULL                COMMENT '操作资源，如 api-keys/3、auth/login',
    detail     TEXT         NULL                    COMMENT '请求体摘要（脱敏后）',
    client_ip  VARCHAR(45)  NULL                    COMMENT '来源 IP',
    status     INT          NOT NULL                COMMENT 'HTTP 响应码',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (id),
    KEY idx_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '管理面审计日志（登录与写操作）';
```

- [ ] **Step 2: AdminUserEntity**(风格对齐 ApiKeyEntity:@TableName、@TableId、getter/setter、中文字段注释)

```java
package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code admin_user} 表实体：管理员账号。
 */
@TableName("admin_user")
public class AdminUserEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 管理员用户名。 */
    private String username;

    /** BCrypt 密码哈希。 */
    private String passwordHash;

    /** 是否启用。 */
    private Boolean enabled;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

(如项目实体统一使用多行 getter/setter 风格,按现有文件排版。)

- [ ] **Step 3: AdminAuditLogEntity**

```java
package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code admin_audit_log} 表实体：管理面审计日志(登录与写操作)。
 */
@TableName("admin_audit_log")
public class AdminAuditLogEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户名(登录失败时为尝试的用户名)。 */
    private String username;

    /** 动作:LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD。 */
    private String action;

    /** 操作资源,如 api-keys/3、auth/login。 */
    private String resource;

    /** 请求体摘要(脱敏后)。 */
    private String detail;

    /** 来源 IP。 */
    private String clientIp;

    /** HTTP 响应码。 */
    private Integer status;

    /** 发生时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getClientIp() { return clientIp; }
    public void setClientIp(String clientIp) { this.clientIp = clientIp; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: 两个 Mapper**(对齐现有 mapper 风格,通常是 `extends BaseMapper<T>` + `@Mapper` 或 MapperScan;先看 `persistence/mapper/ApiKeyMapper.java` 依样)

```java
package com.llm.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.gateway.persistence.entity.AdminUserEntity;

/** {@code admin_user} 表 Mapper。 */
public interface AdminUserMapper extends BaseMapper<AdminUserEntity> {
}
```

```java
package com.llm.gateway.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;

/** {@code admin_audit_log} 表 Mapper。 */
public interface AdminAuditLogMapper extends BaseMapper<AdminAuditLogEntity> {
}
```

(若 ApiKeyMapper 带 `@Mapper` 注解则同样加上。)

- [ ] **Step 5: 对本地库执行新表 DDL 并编译**

Run: `mysql -uroot -p123456 llm_gateway < src/main/resources/schema.sql`(或用现有连接方式执行两条 CREATE TABLE;schema.sql 全部是 IF NOT EXISTS,可整文件重放)
Run: `mvn -q compile`
Expected: 两表创建成功;BUILD SUCCESS。

---

## Task 3: AdminAuthService(TDD:BCrypt / JWT / 锁定 / 引导)

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminPrincipal.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminAuthService.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/auth/admin/AdminAuthServiceTest.java`

- [ ] **Step 1: AdminPrincipal**

```java
package com.llm.gateway.auth.admin;

/**
 * 管理端登录主体(JWT 验签通过后放入请求属性)。
 *
 * @param username 管理员用户名
 */
public record AdminPrincipal(String username) {
}
```

- [ ] **Step 2: 写失败测试 AdminAuthServiceTest**

```java
package com.llm.gateway.auth.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.llm.gateway.config.AdminAuthProperties;
import com.llm.gateway.exception.AuthenticationException;
import com.llm.gateway.persistence.entity.AdminUserEntity;
import com.llm.gateway.persistence.mapper.AdminUserMapper;

class AdminAuthServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef012345";

    private AdminUserMapper mapper;
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminUserMapper.class);
        AdminUserEntity user = new AdminUserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(new BCryptPasswordEncoder().encode("secret-pass"));
        user.setEnabled(true);
        when(mapper.selectOne(any())).thenReturn(user);
        when(mapper.selectCount(any())).thenReturn(1L);
        service = new AdminAuthService(mapper,
                new AdminAuthProperties(SECRET, 120, "", ""), null);
    }

    @Test
    void loginIssuesVerifiableJwt() {
        String token = service.login("admin", "secret-pass", "127.0.0.1");
        Optional<AdminPrincipal> principal = service.verify(token);
        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("admin");
    }

    @Test
    void wrongPasswordRejected() {
        assertThatThrownBy(() -> service.login("admin", "wrong", "127.0.0.1"))
                .isInstanceOf(AuthenticationException.class);
    }

    @Test
    void garbageTokenRejected() {
        assertThat(service.verify("not-a-jwt")).isEmpty();
        assertThat(service.verify(null)).isEmpty();
    }

    @Test
    void lockedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login("admin", "wrong", "127.0.0.1"))
                    .isInstanceOf(AuthenticationException.class);
        }
        // 第 6 次即使密码正确也被锁定
        assertThatThrownBy(() -> service.login("admin", "secret-pass", "127.0.0.1"))
                .isInstanceOf(AdminAuthService.LoginLockedException.class);
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new AdminAuthService(mapper,
                new AdminAuthProperties("short", 120, "", ""), null))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

说明:`AuthenticationException` 为项目已有异常(`com.llm.gateway.exception.AuthenticationException`);构造函数第三个参数是 `AdminAuditService`(Task 5 才建,先声明为可空依赖,null 时跳过审计——见实现)。

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q test -Dtest=AdminAuthServiceTest`
Expected: 编译失败(AdminAuthService 不存在)。

- [ ] **Step 4: 实现 AdminAuthService**

```java
package com.llm.gateway.auth.admin;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.audit.AdminAuditService;
import com.llm.gateway.config.AdminAuthProperties;
import com.llm.gateway.exception.AuthenticationException;
import com.llm.gateway.persistence.entity.AdminUserEntity;
import com.llm.gateway.persistence.mapper.AdminUserMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 管理端账号鉴权服务:BCrypt 密码校验、JWT(HS256)签发与验签、登录防爆破锁定、
 * 首个管理员账号引导。
 *
 * <p>不引入 Spring Security 全家桶,与网关现有手写 Filter 风格一致。
 */
@Service
public class AdminAuthService {

    /** 登录锁定异常:连续失败次数达到上限。 */
    public static class LoginLockedException extends RuntimeException {
        public LoginLockedException(String message) {
            super(message);
        }
    }

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
    /** username -> 失败状态(次数 + 锁定截止时间)。单实例内存即可,多实例部署由 SCA 阶段统一。 */
    private final ConcurrentHashMap<String, FailureState> failures = new ConcurrentHashMap<>();

    private record FailureState(int count, long lockedUntilMs) {
    }

    public AdminAuthService(AdminUserMapper adminUserMapper, AdminAuthProperties properties,
                            @Nullable AdminAuditService auditService) {
        if (properties.jwtSecret() == null || properties.jwtSecret().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "GATEWAY_JWT_SECRET 未配置或长度不足 " + MIN_SECRET_LENGTH + " 字符,拒绝启动");
        }
        this.adminUserMapper = adminUserMapper;
        this.properties = properties;
        this.auditService = auditService;
        this.signingKey = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        bootstrapAdmin();
    }

    /**
     * 登录:校验用户名密码,通过则签发 JWT。
     *
     * @param username 用户名
     * @param password 明文密码
     * @param clientIp 来源 IP(审计用)
     * @return JWT
     * @throws AuthenticationException 用户名或密码错误(不区分,防枚举)
     * @throws LoginLockedException    连续失败被锁定
     */
    public String login(String username, String password, String clientIp) {
        FailureState state = failures.get(username);
        long now = System.currentTimeMillis();
        if (state != null && state.lockedUntilMs() > now) {
            audit(username, "LOGIN_LOCKED", clientIp, 423);
            throw new LoginLockedException("登录失败次数过多,请 5 分钟后再试");
        }
        AdminUserEntity user = adminUserMapper.selectOne(
                Wrappers.<AdminUserEntity>lambdaQuery().eq(AdminUserEntity::getUsername, username));
        boolean ok = user != null && !Boolean.FALSE.equals(user.getEnabled())
                && encoder.matches(password, user.getPasswordHash());
        if (!ok) {
            recordFailure(username, now);
            audit(username, "LOGIN_FAIL", clientIp, 401);
            throw new AuthenticationException("用户名或密码错误");
        }
        failures.remove(username);
        audit(username, "LOGIN_OK", clientIp, 200);
        Date expiry = new Date(now + properties.tokenTtlMinutes() * 60_000L);
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now))
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /**
     * 验签:合法且未过期则返回主体。
     *
     * @param token JWT
     * @return 主体,非法/过期为空
     */
    public Optional<AdminPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String username = Jwts.parser().verifyWith(signingKey).build()
                    .parseSignedClaims(token).getPayload().getSubject();
            return Optional.of(new AdminPrincipal(username));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 引导:admin_user 表为空且配置了引导账号时创建首个管理员。 */
    private void bootstrapAdmin() {
        Long count = adminUserMapper.selectCount(null);
        if (count != null && count > 0) {
            return;
        }
        String username = properties.bootstrapUsername();
        String password = properties.bootstrapPassword();
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("admin_user 表为空且未配置 ADMIN_USERNAME/ADMIN_PASSWORD,管理端将无法登录");
            return;
        }
        AdminUserEntity user = new AdminUserEntity();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        user.setEnabled(true);
        adminUserMapper.insert(user);
        log.info("已创建引导管理员账号 [{}]", username);
    }

    /** 记录一次失败,达到上限则设置锁定截止时间。 */
    private void recordFailure(String username, long now) {
        failures.compute(username, (u, old) -> {
            int count = (old == null || old.lockedUntilMs() > 0 ? 0 : old.count()) + 1;
            long lockedUntil = count >= MAX_FAILURES ? now + LOCK_MILLIS : 0;
            return new FailureState(count, lockedUntil);
        });
    }

    /** 审计(服务未装配时跳过——单元测试场景)。 */
    private void audit(String username, String action, String clientIp, int status) {
        if (auditService != null) {
            auditService.record(username, action, "auth/login", null, clientIp, status);
        }
    }
}
```

注意:`AdminAuditService.record(...)` 在 Task 5 定义;本 Task 编译需要它存在——**先建骨架**(Task 5 Step 1 的类,方法体只有 TODO 时编译不过 No-Placeholder 原则,因此把 Task 5 Step 1 的完整 AdminAuditService 提前到本 Task 一起写)。见下一步。

- [ ] **Step 5: 提前创建完整 AdminAuditService**(Task 5 复用,不重复建)

Create `llm-gateway/src/main/java/com/llm/gateway/audit/AdminAuditService.java`:

```java
package com.llm.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

/**
 * 管理面审计服务:登录事件与写操作入库。写入失败仅告警,不影响业务响应。
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);
    private static final int MAX_DETAIL_LENGTH = 2000;

    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录一条审计。
     *
     * @param username 操作者(登录失败时为尝试的用户名)
     * @param action   动作:LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD
     * @param resource 资源,如 api-keys/3
     * @param detail   请求体摘要(调用方负责脱敏),超长截断
     * @param clientIp 来源 IP
     * @param status   HTTP 响应码
     */
    public void record(String username, String action, String resource,
                       String detail, String clientIp, int status) {
        try {
            AdminAuditLogEntity entity = new AdminAuditLogEntity();
            entity.setUsername(username);
            entity.setAction(action);
            entity.setResource(resource);
            entity.setDetail(detail == null || detail.length() <= MAX_DETAIL_LENGTH
                    ? detail : detail.substring(0, MAX_DETAIL_LENGTH));
            entity.setClientIp(clientIp);
            entity.setStatus(status);
            mapper.insert(entity);
        } catch (RuntimeException e) {
            // 审计失败不应影响业务(与 GatewayService.persistError 同思路)
            log.warn("写入管理审计日志失败:{}", e.getMessage());
        }
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -q test -Dtest=AdminAuthServiceTest`
Expected: 5/5 PASS。

---

## Task 4: 登录接口 + AdminJwtFilter

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminAuthController.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminJwtFilter.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/AuthFilterConfig.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/exception/GlobalExceptionHandler.java`(若 LoginLockedException 需要 423 映射)
- Test: `llm-gateway/src/test/java/com/llm/gateway/auth/admin/AdminJwtFilterTest.java`

- [ ] **Step 1: AdminAuthController**

```java
package com.llm.gateway.auth.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.admin.web.R;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理端登录接口({@code /admin/auth/**})。login 免鉴权(过滤器放行),me 需登录。
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    /** 登录请求体。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** 登录响应:JWT 与用户名。 */
    public record LoginResponse(String token, String username) {
    }

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。
     *
     * @param body    用户名 + 密码
     * @param request 用于取来源 IP
     * @return JWT 与用户名
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String token = authService.login(body.username(), body.password(), request.getRemoteAddr());
        return R.ok(new LoginResponse(token, body.username()));
    }

    /**
     * 当前登录用户(前端启动时校验 token 有效性用)。
     *
     * @param principal 过滤器注入的主体
     * @return 用户名
     */
    @GetMapping("/me")
    public R<String> me(@RequestAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE) AdminPrincipal principal) {
        return R.ok(principal.username());
    }
}
```

- [ ] **Step 2: AdminJwtFilter**

```java
package com.llm.gateway.auth.admin;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.llm.gateway.admin.web.R;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理端 JWT 鉴权过滤器:拦截 {@code /admin/**},放行登录接口与 CORS 预检;
 * 验签通过把 {@link AdminPrincipal} 放入请求属性,失败返回 401(R 包装,前端拦截器识别跳登录)。
 */
public class AdminJwtFilter extends OncePerRequestFilter {

    /** 请求属性名:登录主体。 */
    public static final String ADMIN_PRINCIPAL_ATTRIBUTE = "gateway.adminPrincipal";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGIN_PATH = "/admin/auth/login";

    private final AdminAuthService authService;
    private final ObjectMapper objectMapper;

    public AdminJwtFilter(AdminAuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 放行登录接口与 CORS 预检
        return LOGIN_PATH.equals(request.getRequestURI())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim() : null;
        Optional<AdminPrincipal> principal = authService.verify(token);
        if (principal.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), new R<Void>(401, "未登录或登录已过期", null));
            return;
        }
        request.setAttribute(ADMIN_PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 3: AuthFilterConfig 注册**(追加一个 @Bean,原 apiKeyAuthFilter 不动)

```java
    /**
     * 注册管理端 JWT 鉴权过滤器(/admin/**,登录接口在过滤器内放行)。
     *
     * @param authService  管理端鉴权服务
     * @param objectMapper JSON 序列化器
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<AdminJwtFilter> adminJwtFilter(
            AdminAuthService authService, ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminJwtFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminJwtFilter(authService, objectMapper));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminJwtFilter");
        return registration;
    }
```

import 加 `com.llm.gateway.auth.admin.AdminAuthService`、`com.llm.gateway.auth.admin.AdminJwtFilter`。

- [ ] **Step 4: GlobalExceptionHandler 检查**

读 `exception/GlobalExceptionHandler.java`:确认 `AuthenticationException` 已映射(现有 /v1 用);为 `AdminAuthService.LoginLockedException` 增加 423 映射,返回体用 R 包装(管理端约定):

```java
    /**
     * 管理端登录锁定:HTTP 423。
     */
    @ExceptionHandler(AdminAuthService.LoginLockedException.class)
    public ResponseEntity<R<Void>> handleLoginLocked(AdminAuthService.LoginLockedException e) {
        return ResponseEntity.status(423).body(new R<>(423, e.getMessage(), null));
    }
```

同时确认登录接口抛 `AuthenticationException` 时返回体是什么形状——若现有 handler 返回 ErrorResponse(OpenAI 风格),为 `/admin/auth/login` 的体验一致性,可保持现状(前端 axios 错误拦截读 `error.response.data.msg || error.message`,ErrorResponse 无 msg 字段则显示通用错误)。**决定:给 AuthenticationException 的现有映射保持不动,前端登录页对 401 显示固定文案"用户名或密码错误"**(不依赖响应体)。

- [ ] **Step 5: 集成测试 AdminJwtFilterTest**(@SpringBootTest + MockMvc,需要本地 MySQL,与 LlmGatewayApplicationTests 同法)

```java
package com.llm.gateway.auth.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=it-admin",
        "gateway.admin.bootstrap-password=it-admin-pass"
})
@AutoConfigureMockMvc
class AdminJwtFilterTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuthService authService;

    @Test
    void adminEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/admin/api-keys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void loginPathIsExemptAndBadCredentialsRejected() throws Exception {
        mockMvc.perform(post("/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenPasses() throws Exception {
        String token = issueToken();
        mockMvc.perform(get("/admin/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String issueToken() {
        // 通过登录接口以外的途径构造合法 token:复用服务的签发逻辑需要正确密码,
        // 引导账号仅表空时创建;稳妥做法:verify 一个由测试专用方法签发的 token。
        // 这里直接调用 login,若本地库已有其他管理员导致引导未生效,则跳过该用例。
        try {
            return authService.login("it-admin", "it-admin-pass", "127.0.0.1");
        } catch (RuntimeException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "本地库已有管理员,跳过");
            return null;
        }
    }
}
```

- [ ] **Step 6: 运行**

Run: `mvn -q test -Dtest=AdminJwtFilterTest`
Expected: PASS(或 validTokenPasses 因本地已有管理员而 SKIP——报告注明)。

Run: `mvn -q test`
Expected: 全绿(LlmGatewayApplicationTests 因 Task 1 Step 4 的属性正常启动)。

---

## Task 5: AdminAuditFilter + 审计查询接口

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/audit/AdminAuditFilter.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/audit/AuditAdminController.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/AuthFilterConfig.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/audit/AdminAuditFilterTest.java`

(AdminAuditService 已在 Task 3 Step 5 创建。)

- [ ] **Step 1: AdminAuditFilter**

```java
package com.llm.gateway.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.llm.gateway.auth.admin.AdminJwtFilter;
import com.llm.gateway.auth.admin.AdminPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理面写操作审计过滤器:JWT 过滤器之后执行,记录 {@code /admin/**} 的非 GET 请求
 * (谁 / 何时 / 改了什么 / 来源 IP / 响应码)。登录事件由 {@code AdminAuthService} 自行记录。
 */
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/admin/auth/login";

    private final AdminAuditService auditService;

    public AdminAuditFilter(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 查询不记;登录接口由 AdminAuthService 记(含失败),这里跳过避免重复
        return "GET".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            AdminPrincipal principal =
                    (AdminPrincipal) wrapped.getAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE);
            // 未通过鉴权的请求(401)不记审计——没有可信身份
            if (principal != null) {
                auditService.record(
                        principal.username(),
                        actionOf(wrapped.getMethod()),
                        resourceOf(wrapped.getRequestURI()),
                        sanitize(new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8)),
                        wrapped.getRemoteAddr(),
                        response.getStatus());
            }
        }
    }

    /** HTTP 方法映射为审计动作。 */
    private String actionOf(String method) {
        return switch (method.toUpperCase()) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.toUpperCase();
        };
    }

    /** 去掉 /admin/ 前缀作为资源名,如 /admin/api-keys/3 -> api-keys/3。 */
    private String resourceOf(String uri) {
        String resource = uri.startsWith("/admin/") ? uri.substring("/admin/".length()) : uri;
        // reload 语义单独标记
        return resource.isEmpty() ? "admin" : resource;
    }

    /** 脱敏:密码与完整 Key 不落库。 */
    private String sanitize(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return body
                .replaceAll("(\"password\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(\"apiKey\"\\s*:\\s*\"sk-[^\"]{4})[^\"]*(\")", "$1***$2");
    }
}
```

注:`/admin/meta/reload` 会被记为 `CREATE`(POST);资源名 `meta/reload` 已足够辨识,不为它单设 RELOAD 动作分支(YAGNI;审计动作枚举里保留 RELOAD 供 AdminAuthService 之外未来使用)。

- [ ] **Step 2: AuthFilterConfig 注册**(顺序在 JWT 过滤器之后)

```java
    /**
     * 注册管理面审计过滤器(JWT 之后执行,只记写操作)。
     *
     * @param auditService 审计服务
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<AdminAuditFilter> adminAuditFilter(AdminAuditService auditService) {
        FilterRegistrationBean<AdminAuditFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminAuditFilter(auditService));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("adminAuditFilter");
        return registration;
    }
```

- [ ] **Step 3: AuditAdminController**

```java
package com.llm.gateway.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llm.gateway.admin.web.PageResult;
import com.llm.gateway.admin.web.R;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

/**
 * 管理面审计日志查询接口({@code /admin/audit-logs})。
 */
@RestController
@RequestMapping("/admin/audit-logs")
public class AuditAdminController {

    private final AdminAuditLogMapper mapper;

    public AuditAdminController(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页查询审计日志(时间倒序)。
     *
     * @param username 按用户名精确筛选,可空
     * @param action   按动作精确筛选,可空
     * @param page     页码(1 起)
     * @param size     每页大小
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AdminAuditLogEntity>> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<AdminAuditLogEntity> query = Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .eq(username != null && !username.isBlank(), AdminAuditLogEntity::getUsername, username)
                .eq(action != null && !action.isBlank(), AdminAuditLogEntity::getAction, action)
                .orderByDesc(AdminAuditLogEntity::getId);
        Page<AdminAuditLogEntity> p = mapper.selectPage(new Page<>(page, size), query);
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
}
```

- [ ] **Step 4: 集成测试**

```java
package com.llm.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=it-admin",
        "gateway.admin.bootstrap-password=it-admin-pass"
})
@AutoConfigureMockMvc
class AdminAuditFilterTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AdminAuditLogMapper auditMapper;

    @Test
    void anonymousWriteIsNotAudited() throws Exception {
        long before = auditMapper.selectCount(
                Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getAction, "UPDATE"));
        // 无 token,被 JWT 过滤器 401 拦截,审计过滤器不应记录(无可信身份)
        mockMvc.perform(put("/admin/pricing/999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"x\"}"));
        long after = auditMapper.selectCount(
                Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getAction, "UPDATE"));
        assertThat(after).isEqualTo(before);
    }
}
```

(带 token 的写操作审计验证在 Task 10 手动冒烟覆盖——避免测试污染本地配置表。)

- [ ] **Step 5: 运行**

Run: `mvn -q test -Dtest=AdminAuditFilterTest`
Expected: PASS。

---

## Task 6: API Key 哈希化(生成器 + 服务 + 管理接口 + schema/seed)

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/auth/ApiKeyGenerator.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/auth/ApiKeyGeneratorTest.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/persistence/entity/ApiKeyEntity.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/persistence/repository/ApiKeyRecord.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/persistence/repository/impl/ApiKeyRepositoryImpl.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/ApiKeyService.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/admin/ApiKeyAdminController.java`
- Modify: `llm-gateway/src/main/resources/schema.sql`(api_key 表)
- Modify: `llm-gateway/src/main/resources/seed.sql`
- Modify: `llm-gateway/src/test/java/com/llm/gateway/` 下引用 ApiKeyRecord/ApiKeyEntity 的测试(编译修复)

- [ ] **Step 1: 写 ApiKeyGeneratorTest(失败)**

```java
package com.llm.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generatesPrefixedRandomKey() {
        String key = ApiKeyGenerator.generate();
        assertThat(key).startsWith("sk-gw-").hasSize(6 + 32);
        assertThat(ApiKeyGenerator.generate()).isNotEqualTo(key);
    }

    @Test
    void sha256IsDeterministicHex() {
        String h1 = ApiKeyGenerator.sha256("sk-demo-tenant-a");
        assertThat(h1).hasSize(64).matches("[0-9a-f]+");
        assertThat(ApiKeyGenerator.sha256("sk-demo-tenant-a")).isEqualTo(h1);
        // 与 MySQL SHA2(x,256) 输出一致性:已知值断言
        assertThat(ApiKeyGenerator.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void prefixTakesFirstTwelveChars() {
        assertThat(ApiKeyGenerator.prefixOf("sk-gw-0123456789abcdef")).isEqualTo("sk-gw-012345");
        assertThat(ApiKeyGenerator.prefixOf("short")).isEqualTo("short");
    }
}
```

Run: `mvn -q test -Dtest=ApiKeyGeneratorTest` → 编译失败。

- [ ] **Step 2: 实现 ApiKeyGenerator**

```java
package com.llm.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API Key 生成与哈希工具:服务端生成 {@code sk-gw-} + 32 位随机 hex;
 * 库中只存 SHA-256 哈希与展示前缀,明文仅在创建响应中出现一次。
 */
public final class ApiKeyGenerator {

    /** 生成 Key 的固定前缀。 */
    public static final String KEY_PREFIX = "sk-gw-";
    /** 展示用前缀长度(sk-gw- + 6 位随机)。 */
    private static final int DISPLAY_PREFIX_LENGTH = 12;
    private static final int RANDOM_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {
    }

    /** @return 新的随机 API Key(sk-gw- + 32 位 hex) */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * SHA-256(hex 小写),与 MySQL {@code SHA2(x, 256)} 输出一致。
     *
     * @param key 明文 Key
     * @return 64 位 hex 哈希
     */
    public static String sha256(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 列表展示用前缀(前 12 字符)。
     *
     * @param key 明文 Key
     * @return 前缀
     */
    public static String prefixOf(String key) {
        return key.length() <= DISPLAY_PREFIX_LENGTH ? key : key.substring(0, DISPLAY_PREFIX_LENGTH);
    }
}
```

Run: `mvn -q test -Dtest=ApiKeyGeneratorTest` → 3/3 PASS。

- [ ] **Step 3: ApiKeyEntity 字段替换**

`private String apiKey;`(与 getter/setter)替换为:

```java
    /** API Key 的 SHA-256 哈希(hex,64 位),明文不落库。 */
    private String keyHash;

    /** 展示用前缀,如 sk-gw-a1b2c3。 */
    private String keyPrefix;
```

```java
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
```

类注释里"演示用明文"说明一并更新为哈希存储说明。

- [ ] **Step 4: ApiKeyRecord / ApiKeyRepositoryImpl**

```java
/**
 * API Key 领域记录(仓储层把数据库实体转换成它,屏蔽存储细节)。
 *
 * @param keyHash       密钥的 SHA-256 哈希(hex)
 * @param tenant        租户
 * @param roles         角色列表
 * @param allowedModels 可访问的模型/别名列表,{@code ["*"]} 表示全部
 */
public record ApiKeyRecord(String keyHash, String tenant, List<String> roles, List<String> allowedModels) {
}
```

`ApiKeyRepositoryImpl.findAll()` 映射改为 `new ApiKeyRecord(e.getKeyHash(), e.getTenant(), ...)`。

- [ ] **Step 5: ApiKeyService 改为哈希索引**

`reload()` 中 `index.put(record.apiKey(), ...)` → `index.put(record.keyHash(), ...)`;
`authenticate(...)` 查找改为:

```java
        return Optional.ofNullable(keyIndex.get(ApiKeyGenerator.sha256(apiKey)));
```

类注释补一句:「索引以 SHA-256 哈希为键,明文 Key 不进入内存索引」。

- [ ] **Step 6: ApiKeyAdminController 改造**

```java
    /** 创建响应:实体信息 + 仅此一次返回的完整明文 Key。 */
    public record ApiKeyCreatedView(ApiKeyEntity entity, String apiKey) {
    }

    /**
     * 新增:服务端生成 Key,明文仅在本响应返回一次,库中只存哈希与前缀。
     *
     * @param entity 租户/角色/可用模型/启用(key 字段忽略)
     * @return 实体 + 一次性明文 Key
     */
    @PostMapping
    public R<ApiKeyCreatedView> create(@RequestBody ApiKeyEntity entity) {
        String plainKey = ApiKeyGenerator.generate();
        entity.setId(null);
        entity.setKeyHash(ApiKeyGenerator.sha256(plainKey));
        entity.setKeyPrefix(ApiKeyGenerator.prefixOf(plainKey));
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        mapper.insert(entity);
        refreshService.reloadAll();
        return R.ok(new ApiKeyCreatedView(entity, plainKey));
    }
```

`update(...)` 开头强制忽略客户端提交的 key 字段(防篡改):

```java
        entity.setId(id);
        entity.setKeyHash(null);
        entity.setKeyPrefix(null);
        mapper.updateById(entity);
```

(MyBatis-Plus `updateById` 跳过 null 列,哈希/前缀因此不可被修改。)
import 加 `com.llm.gateway.auth.ApiKeyGenerator`。

- [ ] **Step 7: schema.sql 的 api_key 表改为新结构**(新装环境直接建新表;存量环境靠 Task 7 迁移)

```sql
CREATE TABLE IF NOT EXISTS api_key (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，自增',
    key_hash       CHAR(64)     NOT NULL                COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库',
    key_prefix     VARCHAR(16)  NOT NULL                COMMENT '展示用前缀，如 sk-gw-a1b2c3',
    tenant         VARCHAR(64)  NOT NULL                COMMENT '租户标识，用于限流/配额/成本归因',
    roles          VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '角色列表，逗号分隔（RBAC）',
    allowed_models VARCHAR(512) NOT NULL DEFAULT '*'    COMMENT '可访问的模型/别名，逗号分隔，* 表示全部',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用，0 停用',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_hash (key_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'API Key（哈希存储）与租户/角色/可用模型';
```

- [ ] **Step 8: seed.sql 改用 SHA2 函数**(演示 Key 仍可用)

```sql
INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models) VALUES
    (SHA2('sk-demo-tenant-a', 256), 'sk-demo-tena', 'tenant-a', 'user', '*'),
    (SHA2('sk-demo-tenant-b', 256), 'sk-demo-tena', 'tenant-b', 'user', 'auto,cheap');
```

- [ ] **Step 9: 修复引用了旧字段的测试与代码**

Run: `mvn -q compile 2>&1 | head -30`,对报错处(如测试里 `new ApiKeyRecord("sk-...", ...)` 或 `getApiKey()`)逐一改为 keyHash 语义:测试里构造 `new ApiKeyRecord(ApiKeyGenerator.sha256("sk-test"), ...)`,认证断言用明文 `authenticate("sk-test")`。

- [ ] **Step 10: 全量测试**

Run: `mvn -q test`
Expected: 全绿。注意:本地库 api_key 表仍是旧结构,`LlmGatewayApplicationTests` 会在 Task 7 迁移 Runner 就位前失败(启动时 ApiKeyService 查询 key_hash 列不存在)——**若失败属此原因,先手动对本地库执行**:

```sql
ALTER TABLE api_key
    ADD COLUMN key_hash CHAR(64) NULL,
    ADD COLUMN key_prefix VARCHAR(16) NULL;
UPDATE api_key SET key_hash = SHA2(api_key, 256), key_prefix = LEFT(api_key, 12) WHERE key_hash IS NULL;
```

(保留明文列与可空约束,Task 7 的 Runner 负责收尾;此步只为让测试可跑。)

---

## Task 7: 存量明文 Key 迁移 Runner

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/migration/ApiKeyHashMigration.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/ApiKeyService.java`(@DependsOn)
- Test: `llm-gateway/src/test/java/com/llm/gateway/migration/ApiKeyHashMigrationTest.java`

- [ ] **Step 1: 实现 ApiKeyHashMigration**(JdbcTemplate,构造器中执行,早于依赖它的 Bean)

```java
package com.llm.gateway.migration;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.llm.gateway.auth.ApiKeyGenerator;

/**
 * 存量明文 API Key 一次性迁移:检测 {@code api_key} 表仍存在明文列时,
 * 补建哈希/前缀列 → 逐行回填 → 删除明文列。幂等:新结构库直接跳过。
 *
 * <p>单行回填失败仅记 ERROR 并跳过(该 Key 无法再认证,管理员重建),不阻断启动。
 */
@Component("apiKeyHashMigration")
public class ApiKeyHashMigration {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyHashMigration.class);

    public ApiKeyHashMigration(JdbcTemplate jdbc) {
        migrate(jdbc);
    }

    /** 执行迁移(构造器调用,便于测试直接传入 JdbcTemplate)。 */
    void migrate(JdbcTemplate jdbc) {
        if (!columnExists(jdbc, "api_key")) {
            return; // 已是新结构
        }
        log.info("检测到 api_key 表存在明文列,开始哈希迁移");
        if (!columnExists(jdbc, "key_hash")) {
            jdbc.execute("ALTER TABLE api_key ADD COLUMN key_hash CHAR(64) NULL COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库'");
        }
        if (!columnExists(jdbc, "key_prefix")) {
            jdbc.execute("ALTER TABLE api_key ADD COLUMN key_prefix VARCHAR(16) NULL COMMENT '展示用前缀，如 sk-gw-a1b2c3'");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, api_key FROM api_key WHERE key_hash IS NULL OR key_hash = ''");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            try {
                String plain = (String) row.get("api_key");
                jdbc.update("UPDATE api_key SET key_hash = ?, key_prefix = ? WHERE id = ?",
                        ApiKeyGenerator.sha256(plain), ApiKeyGenerator.prefixOf(plain), id);
                migrated++;
            } catch (RuntimeException e) {
                log.error("api_key id={} 迁移失败,跳过(该 Key 将无法认证,请重建):{}", id, e.getMessage());
            }
        }
        // 收尾:补 NOT NULL 与唯一索引,删除明文列(含旧唯一键)
        jdbc.execute("ALTER TABLE api_key MODIFY key_hash CHAR(64) NOT NULL COMMENT 'API Key 的 SHA-256 哈希（hex），明文不落库'");
        jdbc.execute("ALTER TABLE api_key MODIFY key_prefix VARCHAR(16) NOT NULL COMMENT '展示用前缀，如 sk-gw-a1b2c3'");
        if (!indexExists(jdbc, "uk_key_hash")) {
            jdbc.execute("ALTER TABLE api_key ADD UNIQUE KEY uk_key_hash (key_hash)");
        }
        jdbc.execute("ALTER TABLE api_key DROP COLUMN api_key");
        log.info("api_key 明文迁移完成:{} 行已哈希化,明文列已删除", migrated);
    }

    /** information_schema 探测列是否存在。 */
    private boolean columnExists(JdbcTemplate jdbc, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_key' AND COLUMN_NAME = ?",
                Integer.class, column);
        return count != null && count > 0;
    }

    /** information_schema 探测索引是否存在。 */
    private boolean indexExists(JdbcTemplate jdbc, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'api_key' AND INDEX_NAME = ?",
                Integer.class, index);
        return count != null && count > 0;
    }
}
```

注:MySQL 删除列会连带删除仅含该列的索引(uk_api_key),无需单独 DROP INDEX。若 `spring-jdbc` 未在依赖里(MyBatis-Plus starter 已传递引入 spring-jdbc,`JdbcTemplate` 可用;若编译缺类则在 pom 加 `spring-boot-starter-jdbc`)。

- [ ] **Step 2: ApiKeyService 加 @DependsOn**(保证迁移先于首次 reload)

```java
@Service
@DependsOn("apiKeyHashMigration")
public class ApiKeyService implements ConfigReloadable {
```

import `org.springframework.context.annotation.DependsOn`。

- [ ] **Step 3: 迁移幂等测试**(依赖本地 MySQL;构造临时旧结构表验证——直接操作真表有风险,用影子库名不现实,采用「新结构库跳过」+「幂等重跑」两个可安全执行的断言)

```java
package com.llm.gateway.migration;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=it-admin",
        "gateway.admin.bootstrap-password=it-admin-pass"
})
class ApiKeyHashMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApiKeyHashMigration migration;

    @Test
    void rerunOnMigratedSchemaIsNoop() {
        // 上下文启动时已迁移(或本就是新结构);重复执行必须无害
        assertThatCode(() -> migration.migrate(jdbc)).doesNotThrowAnyException();
        assertThatCode(() -> migration.migrate(jdbc)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 4: 运行**

Run: `mvn -q test`
Expected: 全绿。本地库此时应已是新结构(明文列被 Runner 删除)。用 `mysql -uroot -p123456 llm_gateway -e "SHOW COLUMNS FROM api_key"` 确认无 `api_key` 列、有 `key_hash`/`key_prefix`。

---

## Task 8: 前端登录与 401 处理

**Files:**
- Create: `llm-gateway-ui/src/auth/session.js`
- Create: `llm-gateway-ui/src/views/Login.vue`
- Modify: `llm-gateway-ui/src/api/http.js`
- Modify: `llm-gateway-ui/src/api/index.js`
- Modify: `llm-gateway-ui/src/router/index.js`
- Modify: `llm-gateway-ui/src/App.vue`

- [ ] **Step 1: session.js(token 存取,单一职责)**

```javascript
/**
 * 管理端登录态:token 与用户名存 localStorage。
 */
const TOKEN_KEY = 'gw_admin_token'
const USER_KEY = 'gw_admin_user'

export function getToken() { return localStorage.getItem(TOKEN_KEY) || '' }
export function getUsername() { return localStorage.getItem(USER_KEY) || '' }

export function setSession(token, username) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username)
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}
```

- [ ] **Step 2: http.js 请求带 token、401 跳登录**

在 `import` 区加:

```javascript
import router from '../router'
import { getToken, clearSession } from '../auth/session'
```

`axios.create` 之后加请求拦截器:

```javascript
http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})
```

响应错误拦截器(现有 `(error) => {...}`)改为:

```javascript
  (error) => {
    if (error.response?.status === 401) {
      clearSession()
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      }
      ElMessage.error('未登录或登录已过期')
      return Promise.reject(error)
    }
    const msg = error.response?.data?.msg || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
```

注意:http.js ↔ router 循环引用(router → views → api → http → router)在 Vite ESM 下因均为顶层单例可用;若构建报循环警告,把 `router.push` 改为 `window.location.assign('/login')` 并在报告注明。

- [ ] **Step 3: api/index.js 加 authApi**

```javascript
/** 管理端登录 */
export const authApi = {
  login: (data) => http.post('/admin/auth/login', data),
  me: () => http.get('/admin/auth/me')
}
```

- [ ] **Step 4: Login.vue**

```vue
<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '../api'
import { setSession } from '../auth/session'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    const data = await authApi.login({ ...form })
    setSession(data.token, data.username)
    router.push(String(route.query.redirect || '/dashboard'))
  } catch (e) {
    // 401 文案固定,不依赖响应体(后端登录失败返回 OpenAI 风格错误体)
    if (e?.response?.status === 401) ElMessage.error('用户名或密码错误')
    else if (e?.response?.status === 423) ElMessage.error('登录失败次数过多,请 5 分钟后再试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card surface">
      <div class="login-brand">
        <div class="brand-name">LLM Gateway</div>
        <div class="brand-sub">管理控制台登录</div>
      </div>
      <el-form @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large">
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password>
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: grid;
  place-items: center;
  background: var(--app-sidebar-bg, #f5f7fa);
}
.login-card { width: 360px; padding: 40px 36px 32px; }
.login-brand { text-align: center; margin-bottom: 28px; }
.brand-name { font-size: 22px; font-weight: 700; letter-spacing: -0.02em; }
.brand-sub { color: var(--app-text-secondary, #909399); font-size: 13px; margin-top: 4px; }
</style>
```

注意:401 时 http.js 拦截器也会弹"未登录或登录已过期"——为避免登录页双 toast,拦截器 401 分支加路径判断:`if (router.currentRoute.value.path === '/login') { return Promise.reject(error) }`(登录页的 401 交给 Login.vue 自己提示),放在 clearSession 之后、ElMessage 之前:

```javascript
    if (error.response?.status === 401) {
      clearSession()
      if (router.currentRoute.value.path === '/login') {
        return Promise.reject(error) // 登录页自行提示
      }
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      ElMessage.error('未登录或登录已过期')
      return Promise.reject(error)
    }
```

- [ ] **Step 5: router 加 /login 与全局守卫**

routes 数组开头加(无 meta.title,不进侧边菜单——App.vue 菜单按 meta.title 过滤):

```javascript
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
```

`export default` 改为:

```javascript
const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局守卫:未登录一律去登录页
router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && getToken()) {
    return { path: '/dashboard' }
  }
  return true
})

export default router
```

顶部 `import { getToken } from '../auth/session'`。

- [ ] **Step 6: App.vue 登录页免布局 + 顶栏用户菜单**

script 区加:

```javascript
import { getUsername, clearSession } from './auth/session'

const isLogin = computed(() => route.path === '/login')
const username = computed(() => getUsername())

function logout() {
  clearSession()
  router.push('/login')
}
```

`loadMeta` 的 onMounted 改为登录后才加载(避免登录页触发 401 循环):

```javascript
onMounted(() => { if (!isLogin.value) loadMeta() })
watch(isLogin, (v) => { if (!v) loadMeta() })
```

(`watch` 从 vue 导入。)

template 最外层改为:

```html
<template>
  <router-view v-if="isLogin" />
  <el-container v-else class="layout">
    ...原有全部内容不变...
  </el-container>
</template>
```

header-actions 里"刷新配置"按钮后加:

```html
          <el-dropdown @command="logout">
            <span class="user-chip">
              <el-icon><User /></el-icon>&nbsp;{{ username }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
```

script 加 `import { User } from '@element-plus/icons-vue'`;style 加:

```css
.user-chip { display: inline-flex; align-items: center; cursor: pointer; color: var(--app-text-secondary); font-size: 14px; }
```

- [ ] **Step 7: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

---

## Task 9: 前端 ApiKeys 一次性 Key 弹窗 + 审计页

**Files:**
- Modify: `llm-gateway-ui/src/views/ApiKeys.vue`
- Create: `llm-gateway-ui/src/views/AuditLogs.vue`
- Modify: `llm-gateway-ui/src/router/index.js`
- Modify: `llm-gateway-ui/src/main.js`(菜单图标)
- Modify: `llm-gateway-ui/src/api/index.js`

- [ ] **Step 1: api/index.js 加 auditApi**

```javascript
/** 管理面审计日志 */
export const auditApi = {
  list: (params) => http.get('/admin/audit-logs', { params })
}
```

- [ ] **Step 2: ApiKeys.vue 改造**

script 整体替换为:

```javascript
import { onMounted, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, CopyDocument } from '@element-plus/icons-vue'
import { apiKeyApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

const { loading, rows, dialog, formRef, form, deleting, load, openCreate, openEdit, submit: baseSubmit, remove } =
  useCrudDialog({
    api: apiKeyApi,
    blankForm: () => ({ id: null, tenant: '', roles: 'user', allowedModels: '*', enabled: true }),
    confirmText: (row) => `确认删除 API Key「${row.keyPrefix}…」?`,
    buildPayload: (f) => ({ ...f, enabled: f.enabled !== false })
  })

// 创建成功后后端返回 { entity, apiKey };弹一次性展示对话框
const created = reactive({ visible: false, apiKey: '' })

async function submit() {
  if (dialog.mode === 'create') {
    try {
      await formRef.value.validate()
    } catch {
      return
    }
    dialog.saving = true
    try {
      const data = await apiKeyApi.create({ ...form, enabled: form.enabled !== false })
      dialog.visible = false
      created.apiKey = data.apiKey
      created.visible = true
      load()
    } catch (e) {
      /* 错误已由拦截器提示 */
    } finally {
      dialog.saving = false
    }
  } else {
    await baseSubmit()
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(created.apiKey)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败,请手动选择复制')
  }
}

const rules = {
  tenant: [{ required: true, message: '请输入租户', trigger: 'blur' }],
  allowedModels: [{ required: true, message: '请输入可用模型(逗号分隔,* 表示全部)', trigger: 'blur' }]
}

onMounted(load)
```

template 改动:
1. 表格 `apiKey` 列改为:

```html
        <el-table-column prop="keyPrefix" label="Key 前缀" min-width="160">
          <template #default="{ row }"><code>{{ row.keyPrefix }}…</code></template>
        </el-table-column>
```

2. 表单删除「API Key」输入项(整个 `<el-form-item label="API Key" ...>` 块),新增模式下的说明加在表单顶部:

```html
        <el-alert v-if="dialog.mode === 'create'" type="info" :closable="false" style="margin-bottom:16px"
                  title="Key 由服务端生成,创建成功后仅展示一次" />
```

3. dialog 之后追加一次性展示对话框:

```html
    <el-dialog v-model="created.visible" title="API Key 创建成功" width="560px"
               :close-on-click-modal="false" :close-on-press-escape="false">
      <el-alert type="warning" :closable="false" title="请立即保存,关闭后无法再次查看完整 Key" style="margin-bottom:16px" />
      <div class="key-box">
        <code>{{ created.apiKey }}</code>
        <el-button link type="primary" @click="copyKey"><el-icon><CopyDocument /></el-icon>复制</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="created.visible = false">我已保存</el-button>
      </template>
    </el-dialog>
```

style 加:

```css
.key-box { display: flex; align-items: center; gap: 12px; }
.key-box code { flex: 1; word-break: break-all; }
```

- [ ] **Step 3: AuditLogs.vue**

```vue
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { Search, RefreshLeft } from '@element-plus/icons-vue'
import { auditApi } from '../api'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ username: '', action: '', page: 1, size: 20 })

const actionMeta = {
  LOGIN_OK: { type: 'success', label: '登录成功' },
  LOGIN_FAIL: { type: 'danger', label: '登录失败' },
  LOGIN_LOCKED: { type: 'warning', label: '登录锁定' },
  CREATE: { type: 'primary', label: '新增' },
  UPDATE: { type: 'warning', label: '修改' },
  DELETE: { type: 'danger', label: '删除' },
  RELOAD: { type: 'info', label: '刷新配置' }
}

async function load() {
  loading.value = true
  try {
    const data = await auditApi.list({ ...query })
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}
function search() { query.page = 1; load() }
function reset() { query.username = ''; query.action = ''; query.page = 1; load() }
function onPage(p) { query.page = p; load() }
function onSize(s) { query.size = s; query.page = 1; load() }
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 19) : '—' }

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">操作审计</h2>
        <div class="page-subtitle">管理面登录与写操作记录(admin_audit_log 表)</div>
      </div>
    </div>

    <div class="surface" style="padding:16px">
      <div class="toolbar">
        <el-input v-model="query.username" placeholder="用户名" clearable style="width:150px" @keyup.enter="search" />
        <el-select v-model="query.action" placeholder="动作" clearable style="width:150px">
          <el-option v-for="(m, k) in actionMeta" :key="k" :label="m.label" :value="k" />
        </el-select>
        <el-button type="primary" @click="search"><el-icon><Search /></el-icon>&nbsp;查询</el-button>
        <el-button @click="reset"><el-icon><RefreshLeft /></el-icon>&nbsp;重置</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" style="width:100%" empty-text="暂无审计记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }"><span class="tabular-nums">{{ fmtTime(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column prop="username" label="用户" width="110" />
        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="(actionMeta[row.action] || {}).type || 'info'" effect="light" size="small">
              {{ (actionMeta[row.action] || {}).label || row.action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="resource" label="资源" min-width="160" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.detail || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="clientIp" label="来源 IP" width="130" />
        <el-table-column prop="status" label="状态码" width="90" align="right" />
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :current-page="query.page"
          :page-size="query.size"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="onPage"
          @size-change="onSize" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.pager { display: flex; justify-content: flex-end; margin-top: 16px; }
</style>
```

- [ ] **Step 4: 路由 + 菜单图标**

router routes 的 logs 之后加:

```javascript
  {
    path: '/audit',
    name: 'audit',
    component: () => import('../views/AuditLogs.vue'),
    meta: { title: '操作审计', subtitle: '管理面登录与写操作记录', icon: 'Stamp' }
  }
```

main.js 全局图标列表加 `Stamp`:

```javascript
import { Cpu, DataLine, Key, Share, Money, Tickets, Stamp } from '@element-plus/icons-vue'
// 循环数组同步加 Stamp
```

- [ ] **Step 5: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

---

## Task 10: 最终验证与冒烟

- [ ] **Step 1: 后端全量测试**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn test`
Expected: BUILD SUCCESS,全部通过。

- [ ] **Step 2: 前端构建**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

- [ ] **Step 3: 端到端冒烟**(需本地 MySQL)

```bash
cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 \
  GATEWAY_JWT_SECRET=dev-secret-0123456789abcdef0123456789abcdef \
  ADMIN_USERNAME=admin ADMIN_PASSWORD=admin123 && mvn -q spring-boot:run
```

另开终端验证:

```bash
# 1. 未登录 401
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/admin/api-keys   # 期望 401
# 2. 登录拿 token
curl -s -X POST http://localhost:8080/admin/auth/login -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'                              # 期望 {code:0,data:{token,...}}
# 3. 带 token 访问
curl -s http://localhost:8080/admin/api-keys -H "Authorization: Bearer <token>"  # 期望 {code:0,...}
# 4. 创建 Key(响应含一次性明文 sk-gw-*)
curl -s -X POST http://localhost:8080/admin/api-keys -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" -d '{"tenant":"smoke","roles":"user","allowedModels":"*"}'
# 5. 用新 Key 调 /v1(mock 模型验证哈希认证链路)
curl -s -X POST http://localhost:8080/v1/chat/completions -H "Authorization: Bearer <新key>" \
  -H "Content-Type: application/json" -d '{"model":"cheap","messages":[{"role":"user","content":"hi"}]}'
# 6. 审计有记录
curl -s "http://localhost:8080/admin/audit-logs?size=5" -H "Authorization: Bearer <token>"
# 7. 老的演示 Key 仍可用(迁移验证)
curl -s -X POST http://localhost:8080/v1/chat/completions -H "Authorization: Bearer sk-demo-tenant-a" \
  -H "Content-Type: application/json" -d '{"model":"cheap","messages":[{"role":"user","content":"hi"}]}'
```

前端 `npm run dev`:登录页 → 登录 → 各页正常;退出/过期 401 跳登录;创建 Key 一次性弹窗;审计页有登录与写操作记录。

- [ ] **Step 4: 报告**

汇总:测试数、冒烟结果、部署注意事项(GATEWAY_JWT_SECRET/ADMIN_USERNAME/ADMIN_PASSWORD 环境变量;迁移删除明文列,上线前备份 api_key 表)。

