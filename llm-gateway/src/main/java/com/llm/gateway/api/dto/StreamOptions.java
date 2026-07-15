package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 协议的 {@code stream_options}(仅 stream=true 时有意义)。
 *
 * @param includeUsage 为 true 时,在 [DONE] 前额外发送一个含 usage 的 chunk
 */
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}
