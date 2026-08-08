package com.llm.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Management API write contract for model pricing. */
public record PricingWriteRequest(
        @NotBlank @Size(max = 128) String model,
        @NotNull @PositiveOrZero Double inputPer1k,
        @NotNull @PositiveOrZero Double outputPer1k,
        @PositiveOrZero Double cacheReadPer1k,
        @PositiveOrZero Double cacheWritePer1k) {}
