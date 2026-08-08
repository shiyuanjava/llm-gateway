package com.llm.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.redis.GatewayRedisProperties;
import com.llm.gateway.redis.RedisCommandExecutor;
import com.llm.gateway.redis.RedisCommandMetrics;
import com.llm.gateway.redis.RedisKeyspace;
import com.llm.gateway.redis.RedisRole;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

/** Redis exact-response cache behind the shared keyspace and command failure boundary. */
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "redis")
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxValueBytes;
    private final RedisKeyspace keyspace;
    private final RedisCommandExecutor executor;
    private final RedisCommandMetrics metrics;

    @Autowired
    public RedisResponseCache(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            GatewayProperties gatewayProperties,
            GatewayRedisProperties redisProperties,
            RedisKeyspace keyspace,
            RedisCommandExecutor executor,
            RedisCommandMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(gatewayProperties.cache().ttlSeconds());
        this.maxValueBytes = redisProperties.getCacheMaxValueBytes();
        this.keyspace = keyspace;
        this.executor = executor;
        this.metrics = metrics;
    }

    /** Compatibility constructor for isolated callers that do not use Spring wiring. */
    public RedisResponseCache(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, GatewayProperties gatewayProperties) {
        this(
                redisTemplate,
                objectMapper,
                gatewayProperties,
                new GatewayRedisProperties(),
                new RedisKeyspace(new GatewayRedisProperties()),
                new RedisCommandExecutor(
                        new com.llm.gateway.redis.RedisAvailabilityCircuit(new GatewayRedisProperties()),
                        new RedisCommandMetrics(new SimpleMeterRegistry())),
                new RedisCommandMetrics(new SimpleMeterRegistry()));
    }

    @Override
    public Optional<ChatCompletionResponse> get(String key) {
        String redisKey = keyspace.key("cache", key, "exact");
        try {
            String json = executor.execute(RedisRole.CACHE, "response-cache", "get", () -> redisTemplate
                    .opsForValue()
                    .get(redisKey));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(
                    objectMapper.readValue(json, CachedResponse.class).toResponse());
        } catch (RedisCommandExecutor.RedisCircuitOpenException | RedisCommandExecutor.RedisCommandException failure) {
            log.warn(
                    "Redis response cache read unavailable; treating as miss: {}",
                    failure.getClass().getSimpleName());
            return Optional.empty();
        } catch (RuntimeException corruptValue) {
            log.warn(
                    "Redis response cache value could not be parsed; treating as miss: {}",
                    corruptValue.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ChatCompletionResponse response) {
        final String json;
        try {
            json = objectMapper.writeValueAsString(CachedResponse.of(response));
        } catch (RuntimeException serializationFailure) {
            log.warn(
                    "Redis response cache serialization failed; skipping write: {}",
                    serializationFailure.getClass().getSimpleName());
            return;
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxValueBytes) {
            recordOversize();
            return;
        }
        String redisKey = keyspace.key("cache", key, "exact");
        try {
            executor.execute(RedisRole.CACHE, "response-cache", "set", () -> {
                redisTemplate.opsForValue().set(redisKey, json, ttl);
                return null;
            });
        } catch (RedisCommandExecutor.RedisCircuitOpenException | RedisCommandExecutor.RedisCommandException failure) {
            log.warn(
                    "Redis response cache write unavailable; skipping write: {}",
                    failure.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            log.warn(
                    "Redis response cache write failed; skipping write: {}",
                    failure.getClass().getSimpleName());
        }
    }

    private void recordOversize() {
        try {
            metrics.cacheValueOversize();
        } catch (RuntimeException ignored) {
            // Cache observability must not turn a skipped best-effort write into a request failure.
        }
    }
}
