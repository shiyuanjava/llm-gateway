package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条对话消息（OpenAI Chat 协议）。
 *
 * @param role    角色：{@code system} / {@code user} / {@code assistant}
 * @param content 文本内容
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content) {

    /** 便捷构造一条 user 消息。 */
    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** 便捷构造一条 assistant 消息。 */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** 便捷构造一条 system 消息。 */
    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}
