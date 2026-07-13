package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

class AnthropicStreamReadTest {

    private final AnthropicProvider provider =
            new AnthropicProvider(Fixtures.properties(), new ObjectMapper());

    private Usage read(String sse, List<ChatCompletionChunk> sink) throws IOException {
        try (SseEventReader reader = new SseEventReader(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))) {
            return provider.readAnthropicStream(reader, "claude-opus-4-8", sink::add);
        }
    }

    @Test
    void translatesAnthropicEventsToOpenAiChunks() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_01","role":"assistant","usage":{"input_tokens":25,"output_tokens":1}}}

                event: ping
                data: {"type":"ping"}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"！"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals(4, chunks.size()); // first + 2 content + finish
        assertEquals("msg_01", chunks.get(0).id());
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role());
        assertEquals("你好", chunks.get(1).deltaContent());
        assertEquals("！", chunks.get(2).deltaContent());
        assertEquals("stop", chunks.get(3).choices().get(0).finishReason(), "end_turn 映射为 stop");
        assertEquals("claude-opus-4-8", chunks.get(1).model());
        assertEquals(Usage.of(25, 12), usage);
    }

    @Test
    void errorEventThrowsProviderException() {
        String sse = """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;
        assertThrows(ProviderException.class, () -> read(sse, new ArrayList<>()));
    }

    @Test
    void truncatedStreamStillEmitsFinishAndReturnsKnownUsage() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_02","usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半"}}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason(),
                "流截断时补发结束帧,保证客户端拿到合法收尾");
        assertEquals(10, usage.promptTokens());
        assertEquals(1, usage.completionTokens(), "message_start 携带的 output_tokens 作为已知下限");
    }

    @Test
    void truncatedStreamWithUnknownOutputReturnsNullUsage() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_03","usage":{"input_tokens":10}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半"}}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertNull(usage, "输出用量未知时返回 null,交由调用方估算,避免 0 值当权威导致少计费");
        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason());
    }

    @Test
    void maxTokensStopReasonMapsToLength() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_04","usage":{"input_tokens":5,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"截"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":8}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals("length", chunks.get(chunks.size() - 1).choices().get(0).finishReason(),
                "max_tokens 映射为 length");
        assertEquals(Usage.of(5, 8), usage);
    }

    @Test
    void errorEventAfterContentFramesStillThrows() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_05","usage":{"input_tokens":5,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"内容"}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        assertThrows(ProviderException.class, () -> read(sse, chunks));
        assertEquals(2, chunks.size(), "error 前的首帧与内容帧已回调");
    }

    @Test
    void messageStopWithoutStartStillEmitsFirstFrame() throws IOException {
        String sse = """
                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        read(sse, chunks);

        assertEquals(2, chunks.size(), "缺 message_start 时补发首帧,保证客户端拿到 role 帧");
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role());
        assertEquals("stop", chunks.get(1).choices().get(0).finishReason());
    }

    @Test
    void capturesCacheTokensAndNormalizesPromptByAddition() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_c","usage":{"input_tokens":20,"output_tokens":1,"cache_creation_input_tokens":100,"cache_read_input_tokens":300}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);
        assertEquals(420, usage.promptTokens(), "网关口径 prompt 含缓存：20+100+300");
        assertEquals(300, usage.cacheReadTokens());
        assertEquals(100, usage.cacheCreationTokens());
        assertEquals(5, usage.completionTokens());
    }

    @Test
    void truncatedStreamWithCacheStillNormalizesPromptByAddition() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_t","usage":{"input_tokens":20,"output_tokens":1,"cache_creation_input_tokens":100,"cache_read_input_tokens":300}}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);
        assertEquals(420, usage.promptTokens(), "截断兜底路径同样做加法归一化：20+100+300");
        assertEquals(300, usage.cacheReadTokens());
        assertEquals(100, usage.cacheCreationTokens());
        assertEquals(5, usage.completionTokens());
    }
}
