package com.llm.gateway.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI-compatible chat completion request with explicit boundary validation. */
public record ChatCompletionRequest(
        @NotBlank(message = "model 不能为空") @Size(max = 128, message = "model 不能超过 128 个字符") String model,
        @NotEmpty(message = "messages 不能为空") @Size(max = 256, message = "messages 不能超过 256 条")
                List<@NotNull(message = "message 不能为 null") @Valid ChatMessage> messages,
        @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
                @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
                Double temperature,
        @JsonProperty("top_p")
                @DecimalMin(value = "0.0", message = "top_p 不能小于 0")
                @DecimalMax(value = "1.0", message = "top_p 不能大于 1")
                Double topP,
        @JsonProperty("max_tokens")
                @Min(value = 1, message = "max_tokens 不能小于 1")
                @Max(value = 1_000_000, message = "max_tokens 不能大于 1000000")
                Integer maxTokens,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions) {

    public ChatCompletionRequest withModel(String resolvedModel) {
        return new ChatCompletionRequest(resolvedModel, messages, temperature, topP, maxTokens, stream, streamOptions);
    }

    /** Copy used for upstream streaming calls; usage is always requested for billing. */
    public ChatCompletionRequest forStreamingUpstream() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, true, new StreamOptions(true));
    }

    /** Copy used for non-streaming upstream calls, removing stream-only hints. */
    public ChatCompletionRequest withoutStreamHints() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, null, null);
    }

    public boolean wantsUsageChunk() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }
}
