package com.llm.gateway.provider;

import java.util.function.Consumer;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

/**
 * 模型供应商适配器抽象。
 *
 * <p>这是 Harness「L2 工具层」的体现：把 OpenAI、Anthropic、私有模型等各不相同的调用方式
 * 统一成一个最小接口，业务与编排层都只面向它。新增供应商 = 新增一个实现，其它代码不动。
 */
public interface LlmProvider {

    /** @return 供应商名（需与配置中的 key 一致） */
    String name();

    /**
     * 执行一次对话补全。传入请求的 {@code model} 已是该供应商的物理模型名。
     *
     * @param request 已解析到物理模型的请求
     * @return 统一格式的响应
     * @throws com.llm.gateway.exception.ProviderException 调用失败（可被上层重试/降级）
     */
    ChatCompletionResponse chat(ChatCompletionRequest request);

    /**
     * 流式对话补全：把上游产出翻译成 OpenAI chunk 逐个回调，阻塞至流结束。
     *
     * <p>默认实现供不支持原生流式的供应商自动降级：调用 {@link #chat} 后把完整结果按
     * 「首帧 → 全文内容帧 → 结束帧」一次性回放。onChunk 抛出的非受检异常会原样上抛，
     * 调用方以此中止流（实现方应随之释放上游连接）。重试/换目标仅在首帧尚未写给客户端前安全
     * （调用方需用「已发出首帧」标志判断）。
     *
     * @param request 已解析到物理模型的请求
     * @param onChunk 每个内容帧的消费者（usage 帧不经过它，由返回值交付）
     * @return 上游给出的用量；上游未提供时为 null（由调用方估算兜底）
     * @throws com.llm.gateway.exception.ProviderException 调用失败（可被上层重试/降级）
     */
    default Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        ChatCompletionResponse full = chat(request);
        String finishReason = full.choices() == null || full.choices().isEmpty()
                || full.choices().get(0).finishReason() == null
                ? "stop" : full.choices().get(0).finishReason();
        onChunk.accept(ChatCompletionChunk.first(full.id(), full.created(), full.model()));
        onChunk.accept(ChatCompletionChunk.content(full.id(), full.created(), full.model(), full.firstContent()));
        onChunk.accept(ChatCompletionChunk.finish(full.id(), full.created(), full.model(), finishReason));
        return full.usage();
    }
}
