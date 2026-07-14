package com.llm.gateway.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiStreamReadTest {

    private final OpenAiCompatibleProvider provider =
            new OpenAiCompatibleProvider("openai", "http://localhost:1", "test-key", new ObjectMapper(), null);

    private Usage read(String sse, List<ChatCompletionChunk> sink) throws IOException {
        try (SseEventReader reader =
                new SseEventReader(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))) {
            return provider.readStream(reader, sink::add);
        }
    }

    @Test
    void forwardsContentChunksConsumesUsageAndStopsAtDone() throws IOException {
        String sse =
                """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

                data: [DONE]

                data: {"id":"never","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[]}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals(3, chunks.size(), "usage 帧被网关消费,不进回调;[DONE] 后的数据不读");
        assertEquals("Hi", chunks.get(1).deltaContent());
        assertEquals("stop", chunks.get(2).choices().get(0).finishReason());
        assertEquals(9, usage.totalTokens());
    }

    @Test
    void returnsNullUsageWhenUpstreamOmitsIt() throws IOException {
        String sse =
                """
                data: {"id":"c2","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"X"},"finish_reason":null}]}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        assertNull(read(sse, chunks));
        assertEquals(1, chunks.size());
    }

    @Test
    void capturesUsageAttachedToFinalContentChunk() throws IOException {
        // 智谱 GLM、部分 vLLM 配置把 usage 直接挂在最后一个带 choices 的帧上,而不是单独的空 choices 帧
        String sse =
                """
                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"glm","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"glm","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"total_tokens":6}}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals(2, chunks.size(), "带 usage 的内容帧仍需转发给客户端");
        assertEquals("stop", chunks.get(1).choices().get(0).finishReason());
        assertEquals(6, usage.totalTokens());
    }

    @Test
    void doesNotForwardEmptyChoicesFrameWithoutUsage() throws IOException {
        String sse =
                """
                data: {"id":"c4","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[]}

                data: {"id":"c4","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"Y"},"finish_reason":null}]}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        assertNull(read(sse, chunks));
        assertEquals(1, chunks.size(), "空 choices 且无 usage 的退化帧不转发");
        assertEquals("Y", chunks.get(0).deltaContent());
    }

    @Test
    void malformedJsonPropagatesUnchecked() {
        String sse = """
                data: {not-json

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        assertThrows(RuntimeException.class, () -> read(sse, chunks));
        assertEquals(0, chunks.size());
    }

    @Test
    void capturesCachedTokensFromUsageFrame() throws IOException {
        String sse =
                """
                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"X"},"finish_reason":null}]}

                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102,"prompt_tokens_details":{"cached_tokens":64}}}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);
        assertEquals(100, usage.promptTokens(), "OpenAI 口径 prompt 已含缓存，直接沿用");
        assertEquals(64, usage.cacheReadTokens(), "缓存读明细从 prompt_tokens_details 拆出");
    }
}
