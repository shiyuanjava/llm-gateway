package com.llm.gateway.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Management API write contract for API keys; persistence-only fields are intentionally absent. */
public record ApiKeyWriteRequest(
        @NotBlank @Size(max = 64) String tenant,
        @NotNull @Size(max = 255) String roles,
        @NotBlank @Size(max = 512) String allowedModels,
        @NotNull Boolean enabled) {}
