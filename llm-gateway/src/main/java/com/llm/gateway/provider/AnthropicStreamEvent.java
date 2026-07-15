package com.llm.gateway.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Anthropic Messages 流式事件（仅取网关需要的字段，未知字段忽略）。
 * 事件类型见 type：message_start / content_block_delta / message_delta / message_stop / ping / error。
 *
 * @param type    事件类型
 * @param message message_start 携带的消息头（id、input_tokens 与缓存字段）
 * @param delta   content_block_delta 的文本增量，或 message_delta 的 stop_reason
 * @param usage   message_delta 携带的累计输出用量
 */
record AnthropicStreamEvent(String type, StartMessage message, Delta delta, StreamUsage usage) {

    /** message_start 的 message 字段。 */
    record StartMessage(String id, StreamUsage usage) {}

    /** 双用途 delta：text_delta 时有 type/text，message_delta 时有 stop_reason。 */
    record Delta(String type, String text, @JsonProperty("stop_reason") String stopReason) {}

    /**
     * 流式事件里的用量：与 {@link AnthropicResponse.AnthropicUsage} 不同，字段可缺席
     * （message_delta 的 usage 通常只有 output_tokens，协议演进后还可能携带累计 input/cache 字段），
     * 故全部用可空 Integer。
     */
    record StreamUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {}
}
