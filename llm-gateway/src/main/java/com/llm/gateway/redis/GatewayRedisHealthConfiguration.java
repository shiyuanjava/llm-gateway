package com.llm.gateway.redis;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Exposes separate control/cache readiness contributors without making cache failure fatal. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.redis.health-enabled", havingValue = "true")
public class GatewayRedisHealthConfiguration {

    @Bean(name = "controlRedis")
    HealthIndicator controlRedisHealthIndicator(
            @Qualifier("controlRedisTemplate") StringRedisTemplate template, RedisCommandExecutor executor) {
        return indicator(RedisRole.CONTROL, template, executor);
    }

    @Bean(name = "cacheRedis")
    HealthIndicator cacheRedisHealthIndicator(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate template, RedisCommandExecutor executor) {
        return indicator(RedisRole.CACHE, template, executor);
    }

    private HealthIndicator indicator(RedisRole role, StringRedisTemplate template, RedisCommandExecutor executor) {
        return () -> {
            try {
                String pong = executor.execute(
                        role,
                        "health",
                        "ping",
                        () -> template.execute((RedisCallback<String>) connection -> connection.ping()));
                if (!"PONG".equalsIgnoreCase(pong)) {
                    return Health.down()
                            .withDetail("role", role.tag())
                            .withDetail("reply", "unexpected")
                            .build();
                }
                return Health.up().withDetail("role", role.tag()).build();
            } catch (RuntimeException failure) {
                return Health.down()
                        .withDetail("role", role.tag())
                        .withException(failure)
                        .build();
            }
        };
    }
}
