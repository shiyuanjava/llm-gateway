package com.llm.gateway.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 基于 Redis 的精确缓存：跨重启、多实例共享，TTL 用 Redis 原生过期（SET ... EX）。
 *
 * <p><strong>fail-open</strong>：缓存是加速器不是依赖——Redis 连接失败、超时、数据损坏一律
 * 记 WARN 并按未命中处理，请求照常打上游；命令超时由 {@code spring.data.redis.timeout} 兜底。
 * 存储值为 {@link CachedResponse} 信封的 JSON（保留 Usage 缓存拆分）。
 */
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "redis")
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);

    /** key 前缀，与其它业务共用 Redis 时隔离命名空间。 */
    static final String KEY_PREFIX = "gw:cache:exact:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * @param redisTemplate Redis 模板（字符串键值）
     * @param objectMapper  Spring 管理的 ObjectMapper（与线上协议序列化语义一致）
     * @param properties    网关配置，提供 TTL
     */
    public RedisResponseCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              GatewayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(properties.cache().ttlSeconds());
    }

    @Override
    public Optional<ChatCompletionResponse> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedResponse.class).toResponse());
        } catch (RuntimeException e) {
            log.warn("Redis 缓存读取失败,按未命中处理(fail-open): {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ChatCompletionResponse response) {
        try {
            String json = objectMapper.writeValueAsString(CachedResponse.of(response));
            redisTemplate.opsForValue().set(KEY_PREFIX + key, json, ttl);
        } catch (RuntimeException e) {
            log.warn("Redis 缓存写入失败,本次不缓存(fail-open): {}", e.getMessage());
        }
    }
}
