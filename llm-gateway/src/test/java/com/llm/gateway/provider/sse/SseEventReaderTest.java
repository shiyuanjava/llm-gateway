package com.llm.gateway.provider.sse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SseEventReaderTest {

    private SseEventReader readerOf(String text) {
        return new SseEventReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesSequentialDataEvents() throws IOException {
        try (SseEventReader r = readerOf("data: one\n\ndata: two\n\n")) {
            assertEquals("one", r.next().data());
            assertEquals("two", r.next().data());
            assertNull(r.next());
        }
    }

    @Test
    void toleratesCrLfAndNoSpaceAfterColon() throws IOException {
        try (SseEventReader r = readerOf("data:[DONE]\r\n\r\n")) {
            assertEquals("[DONE]", r.next().data());
        }
    }

    @Test
    void joinsMultiLineDataWithNewline() throws IOException {
        try (SseEventReader r = readerOf("data: a\ndata: b\n\n")) {
            assertEquals("a\nb", r.next().data());
        }
    }

    @Test
    void capturesEventNameAndSkipsComments() throws IOException {
        try (SseEventReader r = readerOf(": keep-alive\nevent: message_start\ndata: {}\n\n")) {
            SseEventReader.SseEvent ev = r.next();
            assertEquals("message_start", ev.event());
            assertEquals("{}", ev.data());
        }
    }

    @Test
    void returnsTrailingEventWithoutFinalBlankLine() throws IOException {
        try (SseEventReader r = readerOf("data: tail")) {
            assertEquals("tail", r.next().data()); // 流意外截断也要吐出半个事件
            assertNull(r.next());
        }
    }

    @Test
    void ignoresIdAndRetryFields() throws IOException {
        try (SseEventReader r = readerOf("id: 42\nretry: 3000\ndata: x\n\n")) {
            SseEventReader.SseEvent ev = r.next();
            assertNull(ev.event());
            assertEquals("x", ev.data());
        }
    }

    @Test
    void eventNameDoesNotLeakIntoNextEvent() throws IOException {
        try (SseEventReader r = readerOf("event: a\ndata: 1\n\ndata: 2\n\n")) {
            assertEquals("a", r.next().event());
            SseEventReader.SseEvent second = r.next();
            assertNull(second.event());
            assertEquals("2", second.data());
        }
    }

    @Test
    void stripsLeadingUtf8Bom() throws IOException {
        try (SseEventReader r = readerOf("\uFEFF" + "data: x\n\n")) {
            assertEquals("x", r.next().data());
        }
    }

    @Test
    void rejectsOversizedEvent() throws IOException {
        try (SseEventReader r = readerOf("data: " + "x".repeat(1_100_000) + "\n\n")) {
            assertThrows(IOException.class, r::next);
        }
    }
}
