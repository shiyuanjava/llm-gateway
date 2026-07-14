package com.llm.gateway.api.dto;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesOnlyThreeProtocolFields() {
        String json = mapper.writeValueAsString(Usage.of(100, 20, 30, 10));
        assertTrue(json.contains("\"prompt_tokens\":100"));
        assertTrue(json.contains("\"completion_tokens\":20"));
        assertTrue(json.contains("\"total_tokens\":120"));
        assertFalse(json.contains("cache"), "缓存字段是内部拆分，不得出现在下游协议中，实际: " + json);
    }

    @Test
    void deserializesOpenAiCachedTokensDetails() {
        String json =
                """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":30,"audio_tokens":0}}""";
        Usage usage = mapper.readValue(json, Usage.class);
        assertEquals(100, usage.promptTokens());
        assertEquals(30, usage.cacheReadTokens());
        assertEquals(0, usage.cacheCreationTokens());
    }

    @Test
    void toleratesMissingDetails() {
        Usage usage = mapper.readValue("{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}", Usage.class);
        assertEquals(0, usage.cacheReadTokens());
        assertEquals(Usage.of(7, 2), usage);
    }

    @Test
    void factoriesComputeTotals() {
        assertEquals(new Usage(5, 3, 8, 0, 0), Usage.of(5, 3));
        assertEquals(new Usage(50, 3, 53, 30, 10), Usage.of(50, 3, 30, 10));
    }

    @Test
    void fallsBackToSumWhenTotalTokensMissing() {
        // 部分兼容网关的 usage 帧不带 total_tokens,不能因此打挂整条流:回退 p+c
        Usage usage = mapper.readValue("{\"prompt_tokens\":7,\"completion_tokens\":2}", Usage.class);
        assertEquals(9, usage.totalTokens());
        assertEquals(Usage.of(7, 2), usage);
    }

    @Test
    void keepsUpstreamTotalEvenWhenInconsistent() {
        // 补缺不是重算:上游给了 total 就原样保留,哪怕与 p+c 不一致(如含推理 token 的口径)
        Usage usage =
                mapper.readValue("{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":999}", Usage.class);
        assertEquals(999, usage.totalTokens());
    }

    @Test
    void toleratesNullCachedTokensInsideDetails() {
        Usage usage = mapper.readValue(
                """
                {"prompt_tokens":7,"completion_tokens":2,"total_tokens":9,
                 "prompt_tokens_details":{"cached_tokens":null}}""",
                Usage.class);
        assertEquals(0, usage.cacheReadTokens());
    }

    @Test
    void roundTripDropsCacheSplitByDesign() {
        // 「只进不出」的显式契约:序列化会抹掉缓存拆分,往返后不再 equals 原对象。
        // 若未来把 Usage 序列化进 Redis 等缓存再读回,缓存拆分会归零——这是已知语义,不是 bug。
        Usage original = Usage.of(100, 20, 30, 10);
        Usage roundTripped = mapper.readValue(mapper.writeValueAsString(original), Usage.class);
        assertEquals(100, roundTripped.promptTokens());
        assertEquals(20, roundTripped.completionTokens());
        assertEquals(120, roundTripped.totalTokens());
        assertEquals(0, roundTripped.cacheReadTokens());
        assertEquals(0, roundTripped.cacheCreationTokens());
        assertFalse(original.equals(roundTripped), "缓存拆分只进不出,往返必然丢失");
    }
}
