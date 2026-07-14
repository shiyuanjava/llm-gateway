package com.llm.gateway.api.dto;

import java.util.List;

/**
 * 网关返回的统一响应体，兼容 OpenAI Chat Completions 协议。
 *
 * @param id      响应 ID
 * @param object  对象类型，固定为 {@code chat.completion}
 * @param created 创建时间（epoch 秒）
 * @param model   实际产出该响应的物理模型名
 * @param choices 候选回复列表
 * @param usage   Token 用量
 */
public record ChatCompletionResponse(
        String id, String object, long created, String model, List<Choice> choices, Usage usage) {

    /**
     * 便捷构造一个只含单条 assistant 回复的响应。
     *
     * @param id           响应 ID
     * @param createdEpoch 创建时间（epoch 秒）
     * @param model        物理模型名
     * @param content      回复文本
     * @param finishReason 结束原因
     * @param usage        Token 用量
     * @return 响应对象
     */
    public static ChatCompletionResponse singleMessage(
            String id, long createdEpoch, String model, String content, String finishReason, Usage usage) {
        Choice choice = new Choice(0, ChatMessage.assistant(content), finishReason);
        return new ChatCompletionResponse(id, "chat.completion", createdEpoch, model, List.of(choice), usage);
    }

    /**
     * 取第一条回复的文本内容，无回复时返回空串。
     *
     * @return 第一条回复文本
     */
    public String firstContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        ChatMessage message = choices.get(0).message();
        return message == null || message.content() == null ? "" : message.content();
    }
}
