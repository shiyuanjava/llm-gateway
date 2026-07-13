package com.llm.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 进入网关的统一请求体，兼容 OpenAI Chat Completions 协议。
 *
 * <p>{@code model} 既可以填物理模型名（如 {@code gpt-4o-mini}），也可以填逻辑别名
 * （如 {@code auto} / {@code smart}），由路由层决定最终落到哪个供应商的哪个模型。
 *
 * @param model       目标模型或别名（必填）
 * @param messages    消息列表（必填、非空）
 * @param temperature 采样温度，可空
 * @param topP        nucleus 采样，可空
 * @param maxTokens     最大输出 Token 数，可空
 * @param stream        是否流式
 * @param streamOptions 流式选项（OpenAI 协议 {@code stream_options}），仅 stream=true 时有意义
 */
public record ChatCompletionRequest(
        @NotBlank(message = "model 不能为空") String model,
        @NotEmpty(message = "messages 不能为空") List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions) {

    /**
     * 返回一个把 {@code model} 替换为指定物理模型的副本，其余字段保持不变。
     * 路由决策后用它把别名请求改写成对具体模型的请求。
     *
     * @param resolvedModel 路由解析出的物理模型名
     * @return 改写后的新请求（不可变）
     */
    public ChatCompletionRequest withModel(String resolvedModel) {
        return new ChatCompletionRequest(resolvedModel, messages, temperature, topP, maxTokens, stream, streamOptions);
    }

    /** 供应商上游流式调用副本:强制 stream=true 且 include_usage=true(网关计费需要)。 */
    public ChatCompletionRequest forStreamingUpstream() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, true, new StreamOptions(true));
    }

    /** 非流式路径副本:清掉 stream 与 stream_options,避免上游拒绝「stream=false + stream_options」。 */
    public ChatCompletionRequest withoutStreamHints() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, null, null);
    }

    /** @return 客户端是否要求 usage 帧 */
    public boolean wantsUsageChunk() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }
}
