package com.llm.gateway.cache;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.redis.GatewayRedisProperties;
import com.llm.gateway.redis.RedisAvailabilityCircuit;
import com.llm.gateway.redis.RedisCommandExecutor;
import com.llm.gateway.redis.RedisCommandMetrics;
import com.llm.gateway.redis.RedisKeyspace;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RedisResponseCacheIntegrationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;
    private static RedisResponseCache cache;
    private static RedisKeyspace keyspace;

    @BeforeAll
    static void startClient() {
        int port = Integer.getInteger("redis.test.port", 16379);
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", port);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);

        GatewayRedisProperties properties = new GatewayRedisProperties();
        properties.setEnvironment("test");
        keyspace = new RedisKeyspace(properties);
        RedisCommandMetrics metrics = new RedisCommandMetrics(new SimpleMeterRegistry());
        RedisCommandExecutor executor = new RedisCommandExecutor(new RedisAvailabilityCircuit(properties), metrics);
        cache = new RedisResponseCache(
                template, new ObjectMapper(), Fixtures.properties(), properties, keyspace, executor, metrics);
    }

    @AfterAll
    static void stopClient() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        connectionFactory.destroy();
    }

    @Test
    void storesAndReadsUsingV2Keyspace() {
        ChatCompletionResponse response =
                ChatCompletionResponse.singleMessage("id", 1L, "mock-model", "hello", "stop", Usage.of(2, 3));

        cache.put("abc", response);

        assertThat(cache.get("abc"))
                .get()
                .extracting(ChatCompletionResponse::firstContent)
                .isEqualTo("hello");
        assertThat(template.hasKey(keyspace.key("cache", "abc", "exact"))).isTrue();
    }
}
