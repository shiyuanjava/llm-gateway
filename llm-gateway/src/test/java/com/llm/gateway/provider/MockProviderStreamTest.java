package com.llm.gateway.provider;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ProviderException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockProviderStreamTest {

    private final MockProvider provider = new MockProvider();

    private ChatCompletionRequest request(String model) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user("你好")), null, null, null, true, null);
    }

    @Test
    void streamsFirstThenContentPiecesThenFinish() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = provider.chatStream(request("mock-small"), chunks::add);

        assertNotNull(usage);
        assertTrue(chunks.size() >= 3, "至少 first + 1 content + finish");
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role());
        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason());
        String full = chunks.stream().map(ChatCompletionChunk::deltaContent).reduce("", String::concat);
        assertTrue(full.contains("[mock:mock-small] 收到：你好"), "分片拼回完整文本,实际: " + full);
    }

    @Test
    void failModelThrows() {
        assertThrows(ProviderException.class, () -> provider.chatStream(request("mock-fail"), c -> {}));
    }

    @Test
    void dirtyModelEmitsSensitiveWordForGuardrailDemo() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        provider.chatStream(request("mock-dirty"), chunks::add);
        String full = chunks.stream().map(ChatCompletionChunk::deltaContent).reduce("", String::concat);
        assertTrue(full.contains("制造炸弹"), "dirty 模型输出需含演示敏感词");
    }

    @Test
    void splitPiecesDoNotBreakSurrogatePairs() {
        // 回显含 emoji(代理对)的输入,分片切点必须落在码点边界,否则 SSE 帧序列化出非法 UTF-16
        ChatCompletionRequest emojiRequest = new ChatCompletionRequest(
                "mock-small", List.of(ChatMessage.user("你好👍👍👍👍👍👍试试")), null, null, null, true, null);
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        provider.chatStream(emojiRequest, chunks::add);

        String full = chunks.stream().map(ChatCompletionChunk::deltaContent).reduce("", String::concat);
        assertEquals("[mock:mock-small] 收到：你好👍👍👍👍👍👍试试", full, "分片拼回完整文本");
        for (ChatCompletionChunk chunk : chunks) {
            String piece = chunk.deltaContent();
            if (!piece.isEmpty()) {
                assertFalse(
                        Character.isHighSurrogate(piece.charAt(piece.length() - 1)), "分片不得以未配对的高代理项结尾,实际分片: " + piece);
            }
        }
    }

    @Test
    void defaultChatStreamReplaysNonStreamingResult() {
        // 匿名实现只提供 chat(),验证接口 default 方法的回放降级
        LlmProvider nonStreaming = new LlmProvider() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public ChatCompletionResponse chat(ChatCompletionRequest r) {
                return ChatCompletionResponse.singleMessage("id-1", 42, r.model(), "整段回复", "stop", Usage.of(2, 3));
            }
        };
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = nonStreaming.chatStream(request("any"), chunks::add);

        assertEquals(3, chunks.size()); // first + content(全文) + finish
        assertEquals("整段回复", chunks.get(1).deltaContent());
        assertEquals(Usage.of(2, 3), usage);
    }

    @Test
    void defaultChatStreamFoldsNullFinishReasonToStop() {
        // 上游给出非空 choice 但 finish_reason 为 null 时,结束帧不得缺失 finish_reason
        LlmProvider nonStreaming = new LlmProvider() {
            @Override
            public String name() {
                return "plain";
            }

            @Override
            public ChatCompletionResponse chat(ChatCompletionRequest r) {
                return ChatCompletionResponse.singleMessage("id-2", 42, r.model(), "整段回复", null, Usage.of(2, 3));
            }
        };
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        nonStreaming.chatStream(request("any"), chunks::add);

        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason());
    }
}
