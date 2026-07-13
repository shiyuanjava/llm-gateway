package com.llm.gateway.core.streaming;

import java.time.Instant;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.guardrail.GuardrailEngine;

/**
 * 流式聚合器：边转发边累计增量文本，并对累计文本做增量出站护栏检查；
 * 流结束后把碎片组装回完整 {@link ChatCompletionResponse}，复用非流式的缓存与计费收尾。
 *
 * <p>护栏命中时在「写出该帧之前」抛出 {@link com.llm.gateway.exception.GuardrailException}，
 * 保证命中帧不会到达客户端（此前的帧已送达，是流式固有限制）。
 *
 * <p>非线程安全：每个流式请求创建一个实例，帧必须串行送入。
 *
 * <p>仅支持单候选流（只读 choices[0]）。
 */
public class StreamAggregator {

    private final GuardrailEngine guardrailEngine;
    private final StringBuilder text = new StringBuilder();

    private String id;
    private String model;
    private long created;
    private String finishReason;

    /** @param guardrailEngine 护栏引擎（出站链） */
    public StreamAggregator(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
    }

    /**
     * 吸收一帧：记录元数据、累计文本、跑增量护栏。
     *
     * @param chunk 供应商翻译后的 OpenAI 帧
     * @throws com.llm.gateway.exception.GuardrailException 累计文本命中出站护栏
     */
    public void accept(ChatCompletionChunk chunk) {
        if (chunk.id() != null) {
            id = chunk.id();
        }
        if (chunk.model() != null) {
            model = chunk.model();
        }
        if (chunk.created() > 0) {
            created = chunk.created();
        }
        if (chunk.choices() != null && !chunk.choices().isEmpty()
                && chunk.choices().get(0).finishReason() != null) {
            finishReason = chunk.choices().get(0).finishReason();
        }
        String delta = chunk.deltaContent();
        if (!delta.isEmpty()) {
            text.append(delta);
            guardrailEngine.checkOutputText(text.toString());
        }
    }

    /** @return 当前累计文本（截断/断开时用于估算已产生用量） */
    public String text() {
        return text.toString();
    }

    /** @return 实际产出的模型名，尚无帧时为 null */
    public String model() {
        return model;
    }

    /**
     * 流结束后组装完整响应（供缓存与 finish() 计费落库）。
     *
     * <p>仅在流正常结束后调用；护栏命中路径不得用于缓存（累计文本已含被拦截内容）。
     *
     * @param usage 最终用量（上游给出或估算）
     * @return 完整响应
     */
    public ChatCompletionResponse buildResponse(Usage usage) {
        return ChatCompletionResponse.singleMessage(
                id == null ? "chatcmpl-stream" : id,
                created > 0 ? created : Instant.now().getEpochSecond(),
                model,
                text.toString(),
                finishReason == null ? "stop" : finishReason,
                usage);
    }
}
