package com.llm.gateway.cache;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactMatchCacheTest {

    private final AtomicLong now = new AtomicLong(0);

    /** TTL=1 秒、时钟可控的缓存。 */
    private ResponseCache cache() {
        return new ExactMatchCache(Fixtures.properties(60, 1, 1_000_000L, 5, 30, 2)) {
            @Override
            protected long clock() {
                return now.get();
            }
        };
    }

    @Test
    void shouldReturnStoredValueBeforeExpiry() {
        ResponseCache cache = cache();
        cache.put("k", response());

        now.set(500);
        assertTrue(cache.get("k").isPresent());
    }

    @Test
    void shouldExpireAfterTtl() {
        ResponseCache cache = cache();
        cache.put("k", response());

        now.set(1_500); // 超过 1 秒 TTL
        assertTrue(cache.get("k").isEmpty());
    }

    @Test
    void shouldReturnEmptyOnMiss() {
        assertTrue(cache().get("missing").isEmpty());
    }

    @Test
    void shouldProduceSameKeyForSameRequestAndDifferForParams() {
        ChatCompletionRequest a =
                new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("hi")), 0.0, null, null, null, null);
        ChatCompletionRequest b =
                new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("hi")), 0.0, null, null, null, null);
        ChatCompletionRequest different =
                new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("hi")), 0.9, null, null, null, null);

        assertEquals(CacheKey.of(a), CacheKey.of(b));
        assertNotEquals(CacheKey.of(a), CacheKey.of(different));
        assertFalse(CacheKey.of(a).isBlank());
    }

    private ChatCompletionResponse response() {
        return ChatCompletionResponse.singleMessage("id", 0, "m", "hello", "stop", Usage.of(1, 1));
    }
}
