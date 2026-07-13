package com.llm.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 流式响应帧(OpenAI 协议),object 固定为 {@code chat.completion.chunk}。
 *
 * <p>标注 NON_NULL 使序列化形状不依赖全局配置:内容帧省略 usage,usage 帧的 choices 为空数组。
 *
 * @param id      响应 ID(同一次生成的所有帧相同)
 * @param object  固定 {@code chat.completion.chunk}
 * @param created 创建时间(epoch 秒)
 * @param model   物理模型名
 * @param choices 增量候选;usage 帧为空数组
 * @param usage   仅 usage 帧非空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<DeltaChoice> choices,
        Usage usage) {

    private static final String OBJECT = "chat.completion.chunk";

    /** 首帧:delta 含 role=assistant 与空 content。 */
    public static ChatCompletionChunk first(String id, long created, String model) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage("assistant", ""), null)), null);
    }

    /** 内容帧:delta 仅含本帧增量文本。 */
    public static ChatCompletionChunk content(String id, long created, String model, String text) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage(null, text), null)), null);
    }

    /** 结束帧:delta 为空对象,携带 finish_reason。 */
    public static ChatCompletionChunk finish(String id, long created, String model, String finishReason) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage(null, null), finishReason)), null);
    }

    /** usage 帧:choices 为空数组,仅在客户端要求 include_usage 时发送。 */
    public static ChatCompletionChunk usageOnly(String id, long created, String model, Usage usage) {
        return new ChatCompletionChunk(id, OBJECT, created, model, List.of(), usage);
    }

    /** @return 第一个候选的增量文本,无则空串 */
    public String deltaContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        ChatMessage delta = choices.get(0).delta();
        return delta == null || delta.content() == null ? "" : delta.content();
    }
}
