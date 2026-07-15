package com.llm.gateway.cache;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.llm.gateway.Fixtures;
import com.llm.gateway.config.GatewayProperties;

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
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
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
