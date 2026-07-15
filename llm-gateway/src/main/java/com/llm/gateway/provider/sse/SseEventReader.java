package com.llm.gateway.provider.sse;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE(text/event-stream)分帧读取器:把字节流切成一个个事件,供各供应商适配器消费。
 *
 * <p>只负责按规范分帧(空行结束事件、data 多行以 \n 拼接、event 行记名、冒号注释行忽略、
 * 容忍 CRLF 与冒号后无空格、剥掉流首至多一个 UTF-8 BOM),不做 JSON 解析、不认识 [DONE]
 * ——那是调用方的协议语义。
 *
 * <p>单个事件的 data 上限为 {@link #MAX_EVENT_CHARS} 字符,超限抛 {@link IOException},
 * 防止坏/恶意上游用不带空行的无尽 data 行把网关内存打爆。
 */
public final class SseEventReader implements Closeable {

    /** 单个事件 data 的最大字符数,超限视为流损坏。 */
    static final int MAX_EVENT_CHARS = 1_048_576;

    /** 一个 SSE 事件:event 行的名字(可空)与拼接后的 data。 */
    public record SseEvent(String event, String data) {}

    private final BufferedReader reader;
    private boolean bomPending = true;

    /** @param in 上游响应体字节流(UTF-8) */
    public SseEventReader(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * 读取下一个事件;流结束返回 null。流在事件中途截断时,把已积累的半个事件吐出。
     *
     * @return 事件,流尽为 null
     * @throws IOException 读取失败(含 socket 读超时)
     */
    public SseEvent next() throws IOException {
        String event = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (bomPending) {
                bomPending = false;
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                    line = line.substring(1); // 规范要求忽略流首至多一个 BOM
                }
            }
            if (line.isEmpty()) {
                if (data != null) {
                    return new SseEvent(event, data.toString());
                }
                event = null; // 连续空行:重置后继续
                continue;
            }
            if (line.startsWith(":")) {
                continue; // 注释/心跳行
            }
            if (line.startsWith("data:")) {
                String value = fieldValue(line, 5);
                data = data == null
                        ? new StringBuilder(value)
                        : data.append('\n').append(value);
                if (data.length() > MAX_EVENT_CHARS) {
                    throw new IOException("SSE 事件超过上限 " + MAX_EVENT_CHARS + " 字符");
                }
            } else if (line.startsWith("event:")) {
                event = fieldValue(line, 6);
            }
            // 其余字段(id:/retry:)网关不需要,忽略
        }
        return data == null ? null : new SseEvent(event, data.toString());
    }

    /** 取字段值:冒号后至多一个空格按规范剥掉。 */
    private static String fieldValue(String line, int prefixLength) {
        String value = line.substring(prefixLength);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    /** 关闭读取器,连带关闭包装的上游 InputStream(调用方无需重复关闭上游响应体)。 */
    @Override
    public void close() throws IOException {
        reader.close();
    }
}
