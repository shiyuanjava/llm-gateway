package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 一个候选回复（OpenAI 协议）。
 *
 * @param index        序号
 * @param message      回复消息
 * @param finishReason 结束原因，如 {@code stop} / {@code length}
 */
public record Choice(int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {}
