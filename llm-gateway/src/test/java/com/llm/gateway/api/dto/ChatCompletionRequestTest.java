package com.llm.gateway.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class ChatCompletionRequestTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ChatCompletionRequest request(Boolean stream, StreamOptions streamOptions) {
        return new ChatCompletionRequest(
                "m", List.of(ChatMessage.user("x")), 0.7, 0.9, 128, stream, streamOptions);
    }

    @Test
    void deserializesStreamAndStreamOptionsFromWireFormat() {
        String json = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}],"
                + "\"stream\":true,\"stream_options\":{\"include_usage\":true}}";
        ChatCompletionRequest req = mapper.readValue(json, ChatCompletionRequest.class);
        assertEquals(Boolean.TRUE, req.stream());
        assertTrue(req.wantsUsageChunk());
    }

    @Test
    void wantsUsageChunkTruthTable() {
        assertFalse(request(true, null).wantsUsageChunk(), "streamOptions=null 应为 false");
        assertFalse(request(true, new StreamOptions(null)).wantsUsageChunk(), "include_usage=null 应为 false");
        assertFalse(request(true, new StreamOptions(false)).wantsUsageChunk(), "include_usage=false 应为 false");
        assertTrue(request(true, new StreamOptions(true)).wantsUsageChunk(), "include_usage=true 应为 true");
    }

    @Test
    void withoutStreamHintsNullsStreamFieldsAndPreservesOthers() {
        ChatCompletionRequest stripped = request(true, new StreamOptions(true)).withoutStreamHints();
        assertNull(stripped.stream());
        assertNull(stripped.streamOptions());
        assertEquals("m", stripped.model());
        assertEquals(List.of(ChatMessage.user("x")), stripped.messages());
        assertEquals(0.7, stripped.temperature());
        assertEquals(0.9, stripped.topP());
        assertEquals(128, stripped.maxTokens());
    }

    @Test
    void forStreamingUpstreamForcesStreamAndIncludeUsage() {
        ChatCompletionRequest upstream = request(null, new StreamOptions(false)).forStreamingUpstream();
        assertEquals(Boolean.TRUE, upstream.stream());
        assertTrue(upstream.wantsUsageChunk(), "即使客户端传 include_usage=false 也应强制为 true");
    }

    @Test
    void withModelPreservesStreamAndStreamOptions() {
        ChatCompletionRequest routed = request(true, new StreamOptions(true)).withModel("gpt-4o");
        assertEquals("gpt-4o", routed.model());
        assertEquals(Boolean.TRUE, routed.stream());
        assertEquals(new StreamOptions(true), routed.streamOptions());
    }
}
