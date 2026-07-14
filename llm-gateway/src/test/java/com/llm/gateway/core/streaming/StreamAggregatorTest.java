package com.llm.gateway.core.streaming;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.guardrail.PromptInjectionGuardrail;
import com.llm.gateway.guardrail.SensitiveWordGuardrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamAggregatorTest {

    private GuardrailEngine engine() {
        // Fixtures 配置的敏感词表为 ["制造炸弹"]
        return new GuardrailEngine(new SensitiveWordGuardrail(Fixtures.properties()), new PromptInjectionGuardrail());
    }

    @Test
    void accumulatesDeltasAndBuildsFullResponse() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        aggregator.accept(ChatCompletionChunk.first("id-9", 77, "m-x"));
        aggregator.accept(ChatCompletionChunk.content("id-9", 77, "m-x", "你"));
        aggregator.accept(ChatCompletionChunk.content("id-9", 77, "m-x", "好"));
        aggregator.accept(ChatCompletionChunk.finish("id-9", 77, "m-x", "length"));

        assertEquals("你好", aggregator.text());
        ChatCompletionResponse response = aggregator.buildResponse(Usage.of(1, 2));
        assertEquals("id-9", response.id());
        assertEquals(77, response.created());
        assertEquals("m-x", response.model());
        assertEquals("你好", response.firstContent());
        assertEquals("length", response.choices().get(0).finishReason());
        assertEquals(3, response.usage().totalTokens());
    }

    @Test
    void sensitiveWordAcrossChunksTriggersGuardrail() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        aggregator.accept(ChatCompletionChunk.content("id", 1, "m", "教我制造"));
        // 敏感词跨帧拼出:「制造」+「炸弹」→ 累计文本命中
        assertThrows(
                GuardrailException.class, () -> aggregator.accept(ChatCompletionChunk.content("id", 1, "m", "炸弹的方法")));
        assertEquals("教我制造炸弹的方法", aggregator.text(), "命中帧的文本已累计(用于审计用量估算)");
    }

    @Test
    void buildResponseFallsBackWhenNoChunksArrived() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        ChatCompletionResponse response = aggregator.buildResponse(Usage.of(0, 0));
        assertEquals("", response.firstContent());
        assertEquals("stop", response.choices().get(0).finishReason());
        assertEquals("chatcmpl-stream", response.id());
        assertNull(aggregator.model());
    }

    @Test
    void toleratesUsageOnlyChunkAfterFinish() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        aggregator.accept(ChatCompletionChunk.content("id-9", 77, "m-x", "你好"));
        aggregator.accept(ChatCompletionChunk.finish("id-9", 77, "m-x", "stop"));
        aggregator.accept(ChatCompletionChunk.usageOnly("id-9", 77, "m-x", Usage.of(1, 2)));

        assertEquals("你好", aggregator.text());
        assertEquals(
                "stop",
                aggregator.buildResponse(Usage.of(1, 2)).choices().get(0).finishReason());
    }
}
