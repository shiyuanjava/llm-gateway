package com.llm.gateway.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Generates validated, environment-scoped Redis keys with a Cluster hash tag. */
@Component
public class RedisKeyspace {

    private static final Pattern SEGMENT = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private final String prefix;

    public RedisKeyspace(GatewayRedisProperties properties) {
        this.prefix = segment(properties.getNamespace()) + ":v2:" + segment(properties.getEnvironment());
    }

    public String key(String domain, String rawScope, String kind) {
        if (rawScope == null || rawScope.isBlank()) {
            throw new IllegalArgumentException("Redis scope 不能为空");
        }
        return prefix + ":" + segment(domain) + ":{" + sha256(rawScope) + "}:" + segment(kind);
    }

    /** Extracts the Cluster hash tag from a key. */
    public String hashTag(String key) {
        int start = key.indexOf('{');
        int end = key.indexOf('}', start + 1);
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Redis key 不含 hash tag");
        }
        return key.substring(start + 1, end);
    }

    private String segment(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("非法 Redis key segment");
        }
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
