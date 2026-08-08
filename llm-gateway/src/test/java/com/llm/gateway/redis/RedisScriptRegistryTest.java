package com.llm.gateway.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;

class RedisScriptRegistryTest {

    @Test
    void cachesClasspathScriptsByPathAndResultType() {
        RedisScriptRegistry registry = new RedisScriptRegistry();

        RedisScript<Long> first = registry.load("redis/test-return-one.lua", Long.class);
        RedisScript<Long> second = registry.load("redis/test-return-one.lua", Long.class);

        assertThat(first).isSameAs(second);
        assertThat(first.getResultType()).isEqualTo(Long.class);
    }
}
