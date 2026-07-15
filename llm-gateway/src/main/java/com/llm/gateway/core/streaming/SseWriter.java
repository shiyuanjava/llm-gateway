package com.llm.gateway.core.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.exception.ClientDisconnectedException;

import tools.jackson.databind.ObjectMapper;

/**
 * SSE 帧写出器：把 chunk 序列化成 {@code data: ...\n\n} 帧并逐帧 flush。
 *
 * <p><strong>懒提交</strong>是关键设计：响应头在第一帧写出时才设置，因此首帧之前的任何失败
 * 仍可走 {@code GlobalExceptionHandler} 返回带状态码的 JSON 错误——与 OpenAI 语义一致。
 * 写出失败（IOException）统一转成 {@link ClientDisconnectedException} 作为客户端断开信号。
 */
public class SseWriter {

    private final HttpServletResponse response;
    private final ObjectMapper objectMapper;

    private OutputStream out;
    private boolean started;
    private long firstFrameNanos;

    /**
     * @param response     Servlet 响应（阻塞直写，虚拟线程上运行）
     * @param objectMapper Spring 配置好的 Jackson 3 ObjectMapper
     */
    public SseWriter(HttpServletResponse response, ObjectMapper objectMapper) {
        this.response = response;
        this.objectMapper = objectMapper;
    }

    /** @return 是否已写出首帧（容错「可否换目标重试」与错误形态「JSON 还是 error 帧」的分界） */
    public boolean started() {
        return started;
    }

    /**
     * @return 首帧写出时刻的纳秒时间戳，未写出为 0（供 TTFT 统计）。
     *         {@link System#nanoTime()} 的原点是任意的，消费方应以 {@link #started()} 判断是否已写出首帧，
     *         而非与 0 比较。
     */
    public long firstFrameNanos() {
        return firstFrameNanos;
    }

    /**
     * 写出一帧 chunk。
     *
     * @param chunk 帧
     * @throws ClientDisconnectedException 客户端已断开
     */
    public void write(ChatCompletionChunk chunk) {
        writeFrame(objectMapper.writeValueAsString(chunk));
    }

    /**
     * 写出错误帧（仅用于首帧已发出后的中途失败）。
     *
     * @param code    机器可读错误码
     * @param message 人类可读信息
     * @throws IllegalStateException       首帧尚未发出（此时应走 GlobalExceptionHandler 返回 JSON 错误）
     * @throws ClientDisconnectedException 客户端已断开
     */
    public void writeError(String code, String message) {
        if (!started) {
            throw new IllegalStateException("错误帧仅用于首帧已发出后");
        }
        writeFrame(objectMapper.writeValueAsString(Map.of(
                "error", Map.of("message", message == null ? "" : message, "type", "gateway_error", "code", code))));
    }

    /**
     * 写出终帧 {@code data: [DONE]}。调用后不应再写帧。
     *
     * @throws ClientDisconnectedException 客户端已断开
     */
    public void done() {
        writeFrame("[DONE]");
    }

    /** 首帧懒提交响应头，之后逐帧写出并 flush。 */
    private void writeFrame(String data) {
        try {
            if (!started) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/event-stream;charset=UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("X-Accel-Buffering", "no");
                out = response.getOutputStream();
                // 刻意在首字节确认写出前置位：容错的「可否换目标重试」以此为界，
                // 而首写失败即客户端已消失，换目标重试没有意义
                started = true;
                firstFrameNanos = System.nanoTime();
            }
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientDisconnectedException("客户端已断开：" + e.getMessage(), e);
        }
    }
}
