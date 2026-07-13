package com.llm.gateway.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import tools.jackson.databind.ObjectMapper;

class RedisResponseCacheTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    // Fixtures 默认 TTL 300s
    private final RedisResponseCache cache =
            new RedisResponseCache(template, new ObjectMapper(), Fixtures.properties());

    @BeforeEach
    void setUp() {
        when(template.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void shouldRoundTripThroughRedisJsonWithKeyPrefixAndTtl() {
        ChatCompletionResponse response = ChatCompletionResponse.singleMessage(
                "id-1", 123L, "mock-small", "hello", "stop", Usage.of(10, 5, 4, 2));
        cache.put("abc", response);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("gw:cache:exact:abc"), json.capture(), eq(Duration.ofSeconds(300)));

        when(valueOps.get("gw:cache:exact:abc")).thenReturn(json.getValue());
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
                .when(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
        ChatCompletionResponse response = ChatCompletionResponse.singleMessage(
                "id-1", 123L, "mock-small", "hello", "stop", Usage.of(1, 1));

        assertDoesNotThrow(() -> cache.put("abc", response));
    }
}
