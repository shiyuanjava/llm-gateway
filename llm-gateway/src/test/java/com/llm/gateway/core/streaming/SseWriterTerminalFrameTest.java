package com.llm.gateway.core.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.llm.gateway.api.dto.ChatCompletionChunk;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 终帧之后的写出契约：{@code data: [DONE]} 之后不得再追加任何帧。
 */
class SseWriterTerminalFrameTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorFrameIsDroppedAfterDone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, objectMapper);
        writer.write(ChatCompletionChunk.first("id", 1L, "m"));
        writer.done();

        writer.writeError("internal_error", "网关内部错误");

        assertThat(writer.completed()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("internal_error");
        assertThat(response.getContentAsString()).endsWith("data: [DONE]\n\n");
    }

    @Test
    void doneIsIdempotent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, objectMapper);
        writer.write(ChatCompletionChunk.first("id", 1L, "m"));
        writer.done();
        writer.done();

        assertThat(countOccurrences(response.getContentAsString(), "data: [DONE]"))
                .isEqualTo(1);
    }

    @Test
    void contentFrameAfterDoneIsProgrammingError() {
        SseWriter writer = new SseWriter(new MockHttpServletResponse(), objectMapper);
        writer.write(ChatCompletionChunk.first("id", 1L, "m"));
        writer.done();

        assertThatThrownBy(() -> writer.write(ChatCompletionChunk.content("id", 1L, "m", "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    private int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
