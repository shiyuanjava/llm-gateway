package com.llm.gateway.cache;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CachedResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPreserveUsageSplitAcrossJsonRoundTrip() {
        ChatCompletionResponse original = response(Usage.of(10, 5, 4, 2));

        String json = mapper.writeValueAsString(CachedResponse.of(original));
        // 对外协议不变:usage 内不出现拆分字段(它们平铺在信封上)
        assertFalse(json.contains("cache_read"));
        ChatCompletionResponse restored =
                mapper.readValue(json, CachedResponse.class).toResponse();

        assertEquals(4, restored.usage().cacheReadTokens());
        assertEquals(2, restored.usage().cacheCreationTokens());
        assertEquals(10, restored.usage().promptTokens());
        assertEquals(15, restored.usage().totalTokens());
        assertEquals("hello", restored.firstContent());
    }

    @Test
    void shouldPreserveUpstreamTotalEvenWhenInconsistent() {
        // 上游给的 total 与 p+c 不一致时原样保留(Usage 契约:补缺不是重算)
        ChatCompletionResponse original = response(new Usage(10, 5, 99, 4, 2));

        String json = mapper.writeValueAsString(CachedResponse.of(original));
        ChatCompletionResponse restored =
                mapper.readValue(json, CachedResponse.class).toResponse();

        assertEquals(99, restored.usage().totalTokens());
        assertEquals(4, restored.usage().cacheReadTokens());
    }

    @Test
    void shouldPassThroughNullUsage() {
        ChatCompletionResponse original = response(null);

        CachedResponse envelope = CachedResponse.of(original);
        assertEquals(0, envelope.cacheReadTokens());

        String json = mapper.writeValueAsString(envelope);
        assertNull(mapper.readValue(json, CachedResponse.class).toResponse().usage());
    }

    @Test
    void shouldReturnSameResponseWhenNoSplit() {
        ChatCompletionResponse original = response(Usage.of(10, 5));
        assertSame(original, CachedResponse.of(original).toResponse());
    }

    private ChatCompletionResponse response(Usage usage) {
        return ChatCompletionResponse.singleMessage("id-1", 123L, "mock-small", "hello", "stop", usage);
    }
}
