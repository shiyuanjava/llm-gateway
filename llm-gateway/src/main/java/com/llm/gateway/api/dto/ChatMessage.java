package com.llm.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A single OpenAI-compatible chat message. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        @NotBlank(message = "role 不能为空")
                @Size(max = 16, message = "role 不能超过 16 个字符")
                @Pattern(regexp = "system|user|assistant|tool", message = "role 仅允许 system、user、assistant、tool")
                String role,
        @NotNull(message = "content 不能为 null") @Size(max = 262_144, message = "content 不能超过 262144 个字符")
                String content) {

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }
}
