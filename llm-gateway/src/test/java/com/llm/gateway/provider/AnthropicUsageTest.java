package com.llm.gateway.provider;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.Usage;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnthropicUsageTest {

    @Test
    void toUsageAddsCacheIntoPrompt() {
        // Anthropic 口径 input 不含缓存 → 网关口径 prompt = 10 + 5(写) + 40(读) = 55
        AnthropicResponse.AnthropicUsage usage = new AnthropicResponse.AnthropicUsage(10, 7, 5, 40);
        assertEquals(Usage.of(55, 7, 40, 5), usage.toUsage());
    }

    @Test
    void toUsageToleratesNullCacheFields() {
        AnthropicResponse.AnthropicUsage usage = new AnthropicResponse.AnthropicUsage(10, 7, null, null);
        assertEquals(Usage.of(10, 7), usage.toUsage());
    }

    @Test
    void jsonMapsSnakeCaseCacheFields() {
        // 防 @JsonProperty 拼错静默丢缓存：直接从 Anthropic 形态 JSON 反序列化验证映射
        AnthropicResponse.AnthropicUsage usage = new ObjectMapper()
                .readValue(
                        """
                {"input_tokens":10,"output_tokens":7,"cache_creation_input_tokens":5,"cache_read_input_tokens":40}""",
                        AnthropicResponse.AnthropicUsage.class);
        assertEquals(new AnthropicResponse.AnthropicUsage(10, 7, 5, 40), usage);
    }
}
