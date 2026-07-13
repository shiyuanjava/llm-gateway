package com.llm.gateway.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class ChatCompletionChunkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void contentChunkSerializesDeltaContentOnly() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.content("c1", 100, "m1", "你好"));
        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(json.contains("\"delta\":{\"content\":\"你好\"}"));
        assertFalse(json.contains("usage"), "内容帧不应含 usage");
        assertFalse(json.contains("finish_reason"), "NON_NULL 下 null finish_reason 应省略");
    }

    @Test
    void finishChunkSerializesEmptyDelta() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.finish("c1", 100, "m1", "stop"));
        assertTrue(json.contains("\"delta\":{}"));
        assertTrue(json.contains("\"finish_reason\":\"stop\""));
    }

    @Test
    void usageChunkHasEmptyChoices() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.usageOnly("c1", 100, "m1", Usage.of(3, 5)));
        assertTrue(json.contains("\"choices\":[]"));
        assertTrue(json.contains("\"total_tokens\":8"));
    }

    @Test
    void deltaContentExtractsTextSafely() {
        assertEquals("hi", ChatCompletionChunk.content("c", 1, "m", "hi").deltaContent());
        assertEquals("", ChatCompletionChunk.finish("c", 1, "m", "stop").deltaContent());
        assertEquals("", ChatCompletionChunk.usageOnly("c", 1, "m", Usage.of(1, 1)).deltaContent());
    }

    @Test
    void openAiChunkJsonDeserializes() {
        String json = "{\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\","
                + "\"system_fingerprint\":\"fp\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"A\"},"
                + "\"logprobs\":null,\"finish_reason\":null}]}";
        ChatCompletionChunk chunk = mapper.readValue(json, ChatCompletionChunk.class);
        assertEquals("A", chunk.deltaContent()); // 未知字段(system_fingerprint/logprobs)被容忍
    }
}
