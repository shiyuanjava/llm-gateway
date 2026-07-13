package com.llm.gateway.provider;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ProviderException;

/**
 * 本地 Mock 供应商：不依赖外网，直接根据输入构造一个确定性的回复。
 *
 * <p>它有两个用途：(1) 作为路由链最末端的<strong>兜底</strong>，保证真实供应商全部不可用时网关仍能响应；
 * (2) 让单元测试与本地联调无需任何 API Key 即可跑通。若模型名包含 {@code fail}，则模拟一次失败，
 * 便于演示重试与 Fallback。此外，若模型名包含 {@code dirty}，则<strong>仅流式路径</strong>
 * （{@link #chatStream}）输出一段含演示敏感词的固定回复，用于验证流式护栏截断；非流式 {@link #chat} 不受影响。
 */
@Component
public class MockProvider implements LlmProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        if (request.model() != null && request.model().contains("fail")) {
            throw new ProviderException("Mock 供应商模拟失败：" + request.model());
        }
        String lastUser = lastUserContent(request.messages());
        String content = "[mock:" + request.model() + "] 收到：" + lastUser;
        int promptTokens = TokenEstimator.estimate(request.messages());
        int completionTokens = TokenEstimator.estimate(content);
        return ChatCompletionResponse.singleMessage(
                "chatcmpl-mock-" + UUID.randomUUID(),
                Instant.now().getEpochSecond(),
                request.model(),
                content,
                "stop",
                Usage.of(promptTokens, completionTokens));
    }

    /**
     * 取最后一条 user 消息内容。
     *
     * @param messages 消息列表
     * @return 最后一条用户消息，无则空串
     */
    private String lastUserContent(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                String content = messages.get(i).content();
                return content == null ? "" : content;
            }
        }
        return "";
    }

    /** 演示用敏感词回复：模型名含 dirty 时输出含敏感词的文本，用于验证流式护栏截断。 */
    private static final String DIRTY_DEMO_REPLY = "本回复用于演示流式护栏截断，接下来出现敏感词：制造炸弹，其后的内容不应到达客户端。";

    @Override
    public Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        if (request.model() != null && request.model().contains("fail")) {
            throw new ProviderException("Mock 供应商模拟失败：" + request.model());
        }
        String content = request.model() != null && request.model().contains("dirty")
                ? DIRTY_DEMO_REPLY
                : "[mock:" + request.model() + "] 收到：" + lastUserContent(request.messages());
        String id = "chatcmpl-mock-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        onChunk.accept(ChatCompletionChunk.first(id, created, request.model()));
        for (String piece : splitIntoPieces(content)) {
            onChunk.accept(ChatCompletionChunk.content(id, created, request.model(), piece));
        }
        onChunk.accept(ChatCompletionChunk.finish(id, created, request.model(), "stop"));
        return Usage.of(TokenEstimator.estimate(request.messages()), TokenEstimator.estimate(content));
    }

    /**
     * 把文本按码点大致均分为 3 片（不足 3 个码点时整段一片），模拟流式分帧。
     * 切点对齐码点边界，避免把 emoji 等代理对从中间切开产生非法 UTF-16。
     *
     * @param content 完整文本
     * @return 非空分片列表
     */
    private static List<String> splitIntoPieces(String content) {
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints < 3) {
            return List.of(content);
        }
        int cut1 = content.offsetByCodePoints(0, codePoints / 3);
        int cut2 = content.offsetByCodePoints(cut1, codePoints / 3);
        return List.of(
                content.substring(0, cut1),
                content.substring(cut1, cut2),
                content.substring(cut2));
    }
}
