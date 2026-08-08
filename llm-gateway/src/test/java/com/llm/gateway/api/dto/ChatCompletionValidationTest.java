package com.llm.gateway.api.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCompletionValidationTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsNullMessageAndInvalidNestedFields() {
        ChatCompletionRequest request =
                new ChatCompletionRequest("m", List.of(new ChatMessage("invalid", null)), null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("messages[0].role", "messages[0].content");
    }

    @Test
    void rejectsOutOfRangeSamplingAndTokenParameters() {
        ChatCompletionRequest request =
                new ChatCompletionRequest("m", List.of(ChatMessage.user("x")), 2.1, 1.1, 0, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("temperature", "topP", "maxTokens");
    }

    @Test
    void rejectsTooManyMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            messages.add(ChatMessage.user("x"));
        }
        ChatCompletionRequest request = new ChatCompletionRequest("m", messages, null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("messages");
    }

    @Test
    void acceptsSupportedBoundaryValues() {
        ChatCompletionRequest request =
                new ChatCompletionRequest("m", List.of(new ChatMessage("tool", "")), 0.0, 1.0, 1_000_000, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }
}
