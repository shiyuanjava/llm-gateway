package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 流式帧里的一个候选(OpenAI 协议):与 {@link Choice} 的区别是增量放在 {@code delta} 而非 message。
 *
 * @param index        序号
 * @param delta        本帧增量(role 仅首帧出现,content 逐帧追加;结束帧为空对象)
 * @param finishReason 结束原因,仅结束帧非空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaChoice(int index, ChatMessage delta, @JsonProperty("finish_reason") String finishReason) {}
