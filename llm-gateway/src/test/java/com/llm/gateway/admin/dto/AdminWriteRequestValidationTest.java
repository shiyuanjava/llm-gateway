package com.llm.gateway.admin.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminWriteRequestValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsInvalidApiKeyAndPricingRequests() {
        assertThat(validator.validate(new ApiKeyWriteRequest("", "user", "*", true)))
                .isNotEmpty();
        assertThat(validator.validate(new PricingWriteRequest("m", -0.1, 0.0, null, null)))
                .isNotEmpty();
    }

    @Test
    void rejectsPartialEscalationAndInvalidFallback() {
        RoutingRuleWriteRequest request = new RoutingRuleWriteRequest(
                "auto", "mock", "small", 100, "mock", "", List.of(new RoutingRuleWriteRequest.Fallback("", "m")));

        assertThat(validator.validate(request)).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void rejectsMoreThanThirtyTwoFallbacks() {
        List<RoutingRuleWriteRequest.Fallback> fallbacks = new ArrayList<>();
        for (int i = 0; i < 33; i++) {
            fallbacks.add(new RoutingRuleWriteRequest.Fallback("mock", "m-" + i));
        }
        RoutingRuleWriteRequest request =
                new RoutingRuleWriteRequest("auto", "mock", "small", null, null, null, fallbacks);

        assertThat(validator.validate(request)).isNotEmpty();
    }
}
