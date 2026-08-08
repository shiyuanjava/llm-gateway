package com.llm.gateway.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.llm.gateway.Fixtures;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.redis.GatewayRedisProperties;
import com.llm.gateway.redis.RedisAvailabilityCircuit;
import com.llm.gateway.redis.RedisCommandExecutor;
import com.llm.gateway.redis.RedisCommandMetrics;
import com.llm.gateway.redis.RedisKeyspace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * gateway.cache.store 的条件装配:memory(含缺省)装 ExactMatchCache,redis 装 RedisResponseCache,
 * 任何取值下 ResponseCache 有且只有一个实现。
 */
class CacheStoreWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GatewayProperties.class, Fixtures::properties)
            .withBean(GatewayRedisProperties.class, GatewayRedisProperties::new)
            .withBean(RedisKeyspace.class, () -> new RedisKeyspace(new GatewayRedisProperties()))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedisCommandMetrics.class, () -> new RedisCommandMetrics(new SimpleMeterRegistry()))
            .withBean(RedisAvailabilityCircuit.class, () -> new RedisAvailabilityCircuit(new GatewayRedisProperties()))
            .withBean(
                    RedisCommandExecutor.class,
                    () -> new RedisCommandExecutor(
                            new RedisAvailabilityCircuit(new GatewayRedisProperties()),
                            new RedisCommandMetrics(new SimpleMeterRegistry())))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean("cacheRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withUserConfiguration(ExactMatchCache.class, RedisResponseCache.class);

    @Test
    void shouldDefaultToMemoryWhenStoreUnset() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(ExactMatchCache.class);
        });
    }

    @Test
    void shouldWireMemoryExplicitly() {
        runner.withPropertyValues("gateway.cache.store=memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(ExactMatchCache.class);
        });
    }

    @Test
    void shouldWireRedisWhenConfigured() {
        runner.withPropertyValues("gateway.cache.store=redis").run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(RedisResponseCache.class);
        });
    }
}
