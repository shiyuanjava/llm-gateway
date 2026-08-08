package com.llm.gateway.admin.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Management API write contract for a routing rule and its ordered fallback chain. */
public record RoutingRuleWriteRequest(
        @NotBlank @Size(max = 64) String alias,
        @NotBlank @Size(max = 64) String primaryProvider,
        @NotBlank @Size(max = 128) String primaryModel,
        @PositiveOrZero Integer maxPromptTokens,
        @Size(max = 64) String escalateProvider,
        @Size(max = 128) String escalateModel,
        @Size(max = 32) List<@NotNull @Valid Fallback> fallbacks) {

    public RoutingRuleWriteRequest {
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    @AssertTrue(message = "升级阈值、供应商和模型必须同时提供或同时留空")
    public boolean isEscalationComplete() {
        boolean threshold = maxPromptTokens != null;
        boolean provider = hasText(escalateProvider);
        boolean model = hasText(escalateModel);
        return (!threshold && !provider && !model) || (threshold && provider && model);
    }

    public String normalizedEscalateProvider() {
        return hasText(escalateProvider) ? escalateProvider.trim() : null;
    }

    public String normalizedEscalateModel() {
        return hasText(escalateModel) ? escalateModel.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Fallback(@NotBlank @Size(max = 64) String provider, @NotBlank @Size(max = 128) String model) {}
}
