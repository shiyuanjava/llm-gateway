package com.llm.gateway.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatewayRedisHealthConfigurationTest {

    @Test
    void reportsControlAndCacheHealthIndependently() {
        GatewayRedisHealthConfiguration configuration = new GatewayRedisHealthConfiguration();
        StringRedisTemplate control = mock(StringRedisTemplate.class);
        StringRedisTemplate cache = mock(StringRedisTemplate.class);
        RedisCommandExecutor executor = mock(RedisCommandExecutor.class);
        when(executor.execute(
                        org.mockito.ArgumentMatchers.eq(RedisRole.CONTROL),
                        org.mockito.ArgumentMatchers.eq("health"),
                        org.mockito.ArgumentMatchers.eq("ping"),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn("PONG");
        when(executor.execute(
                        org.mockito.ArgumentMatchers.eq(RedisRole.CACHE),
                        org.mockito.ArgumentMatchers.eq("health"),
                        org.mockito.ArgumentMatchers.eq("ping"),
                        org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("cache down"));

        Health controlHealth =
                configuration.controlRedisHealthIndicator(control, executor).health();
        Health cacheHealth =
                configuration.cacheRedisHealthIndicator(cache, executor).health();

        assertThat(controlHealth.getStatus().getCode()).isEqualTo("UP");
        assertThat(cacheHealth.getStatus().getCode()).isEqualTo("DOWN");
    }
}
