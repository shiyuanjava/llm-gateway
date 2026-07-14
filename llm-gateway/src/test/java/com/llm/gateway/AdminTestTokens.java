package com.llm.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 集成测试公共夹具：用测试密钥直接签发管理端 JWT（过滤器只验签不查库）。
 * 使用方须以 {@code "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET} 覆盖配置。
 */
public final class AdminTestTokens {

    public static final String TEST_SECRET = "test-secret-0123456789abcdef0123456789abcdef";

    private AdminTestTokens() {}

    /** @return 有效期 60s 的合法管理端 JWT */
    public static String issue() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("it-admin")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
