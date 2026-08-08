package com.llm.gateway.cache;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.redis.GatewayRedisProperties;
import com.llm.gateway.redis.RedisAvailabilityCircuit;
import com.llm.gateway.redis.RedisCommandExecutor;
import com.llm.gateway.redis.RedisCommandMetrics;
import com.llm.gateway.redis.RedisKeyspace;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedisResponseCacheTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

    private final GatewayRedisProperties redisProperties = new GatewayRedisProperties();
    private final RedisKeyspace keyspace = new RedisKeyspace(redisProperties);
    private final RedisCommandMetrics metrics = mock(RedisCommandMetrics.class);
    private final RedisCommandExecutor executor =
            new RedisCommandExecutor(new RedisAvailabilityCircuit(redisProperties), metrics);
    private RedisResponseCache cache;

    @BeforeEach
    void setUp() {
        when(template.opsForValue()).thenReturn(valueOps);
        cache = new RedisResponseCache(
                template, new ObjectMapper(), Fixtures.properties(), redisProperties, keyspace, executor, metrics);
    }

    @Test
    void shouldRoundTripThroughRedisJsonWithV2KeyAndTtl() {
        ChatCompletionResponse response = ChatCompletionResponse.singleMessage(
                "id-1", 123L, "mock-small", "hello", "stop", Usage.of(10, 5, 4, 2));
        String redisKey = keyspace.key("cache", "abc", "exact");
        cache.put("abc", response);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(redisKey), json.capture(), eq(Duration.ofSeconds(300)));

        when(valueOps.get(redisKey)).thenReturn(json.getValue());
        Optional<ChatCompletionResponse> restored = cache.get("abc");

        assertTrue(restored.isPresent());
        assertEquals("hello", restored.get().firstContent());
        assertEquals(4, restored.get().usage().cacheReadTokens());
        assertEquals(2, restored.get().usage().cacheCreationTokens());
    }

    @Test
    void shouldReturnEmptyOnMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertTrue(cache.get("missing").isEmpty());
    }

    @Test
    void shouldFailOpenWhenRedisGetThrows() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        assertTrue(cache.get("abc").isEmpty());
    }

    @Test
    void shouldFailOpenOnCorruptJson() {
        when(valueOps.get(anyString())).thenReturn("not-json{");
        assertTrue(cache.get("abc").isEmpty());
    }

    @Test
    void shouldFailOpenWhenRedisPutThrows() {
        doThrow(new RuntimeException("connection refused"))
                .when(valueOps)
                .set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
        ChatCompletionResponse response =
                ChatCompletionResponse.singleMessage("id-1", 123L, "mock-small", "hello", "stop", Usage.of(1, 1));

        assertDoesNotThrow(() -> cache.put("abc", response));
    }

    @Test
    void skipsOversizedValueBeforeRedisWrite() {
        redisProperties.setCacheMaxValueBytes(16);
        cache = new RedisResponseCache(
                template, new ObjectMapper(), Fixtures.properties(), redisProperties, keyspace, executor, metrics);
        ChatCompletionResponse response =
                ChatCompletionResponse.singleMessage("id-1", 123L, "mock-small", "hello", "stop", Usage.of(1, 1));

        cache.put("abc", response);

        verifyNoInteractions(valueOps);
        verify(metrics).cacheValueOversize();
    }

    @Test
    void oversizeMetricFailureDoesNotBreakCacheFailOpen() {
        redisProperties.setCacheMaxValueBytes(16);
        cache = new RedisResponseCache(
                template, new ObjectMapper(), Fixtures.properties(), redisProperties, keyspace, executor, metrics);
        doThrow(new IllegalStateException("metrics unavailable")).when(metrics).cacheValueOversize();
        ChatCompletionResponse response =
                ChatCompletionResponse.singleMessage("id-1", 123L, "mock-small", "hello", "stop", Usage.of(1, 1));

        assertDoesNotThrow(() -> cache.put("abc", response));

        verifyNoInteractions(valueOps);
    }
}
