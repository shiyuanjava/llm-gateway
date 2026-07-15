package com.llm.gateway.core.streaming;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ClientDisconnectedException;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void lazyCommitsHeadersOnFirstFrame() throws UnsupportedEncodingException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        assertFalse(writer.started(), "未写帧前不应提交响应头");

        writer.write(ChatCompletionChunk.content("c1", 1, "m", "hi"));

        assertTrue(writer.started());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertTrue(response.getContentAsString().startsWith("data: {"));
        assertTrue(response.getContentAsString().endsWith("\n\n"));
    }

    @Test
    void writesUsageErrorAndDoneFrames() throws UnsupportedEncodingException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        writer.write(ChatCompletionChunk.usageOnly("c1", 1, "m", Usage.of(1, 2)));
        writer.writeError("content_filtered", "出站内容被拦截");
        writer.done();

        String body = response.getContentAsString();
        assertTrue(body.contains("\"total_tokens\":3"));
        assertTrue(body.contains("\"error\":"));
        assertTrue(body.contains("\"code\":\"content_filtered\""));
        assertTrue(body.contains("\"type\":\"gateway_error\""));
        assertTrue(body.endsWith("data: [DONE]\n\n"));
    }

    @Test
    void recordsFirstFrameNanos() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        assertEquals(0, writer.firstFrameNanos());
        writer.write(ChatCompletionChunk.content("c", 1, "m", "x"));
        assertTrue(writer.firstFrameNanos() > 0);
    }

    @Test
    void mapsIoExceptionToClientDisconnected() {
        IOException broken = new IOException("Broken pipe");
        MockHttpServletResponse response = new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        // no-op
                    }

                    @Override
                    public void write(int b) throws IOException {
                        throw broken;
                    }

                    @Override
                    public void flush() throws IOException {
                        throw broken;
                    }
                };
            }
        };
        SseWriter writer = new SseWriter(response, mapper);

        ClientDisconnectedException ex = assertThrows(
                ClientDisconnectedException.class, () -> writer.write(ChatCompletionChunk.content("c", 1, "m", "x")));

        assertSame(broken, ex.getCause(), "应保留原始 IOException 作为 cause");
        // 刻意语义：started 在首字节确认写出前即置位——首写失败说明客户端已消失，不应再换目标重试
        assertTrue(writer.started());
    }
}
