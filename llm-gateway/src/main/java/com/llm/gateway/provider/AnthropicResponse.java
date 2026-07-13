package com.llm.gateway.provider;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.llm.gateway.api.dto.Usage;

/**
 * Anthropic Messages API 的响应结构（仅取网关需要的字段）。
 *
 * <p>用 {@link JsonProperty} 显式映射 snake_case 字段（全局已是 camelCase）；未知字段被忽略。
 *
 * @param id         响应 ID
 * @param model      模型名
 * @param content    内容块数组
 * @param stopReason 结束原因
 * @param usage      用量
 */
public record AnthropicResponse(
        String id,
        String model,
        List<ContentBlock> content,
        @JsonProperty("stop_reason") String stopReason,
        AnthropicUsage usage) {

    /**
     * 内容块。
     *
     * @param type 类型（如 {@code text}）
     * @param text 文本
     */
    public record ContentBlock(String type, String text) {
    }

    /**
     * 用量。Anthropic 口径：{@code input_tokens} <strong>不含</strong>缓存，
     * 缓存读/写是并列字段——与网关口径（prompt 含缓存）相反，用 {@link #toUsage()} 做加法归一化。
     *
     * @param inputTokens              输入 Token（不含缓存）
     * @param outputTokens             输出 Token
     * @param cacheCreationInputTokens 缓存写 Token（可缺席）
     * @param cacheReadInputTokens     缓存读 Token（可缺席）
     */
    public record AnthropicUsage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {

        /** @return 网关口径用量（prompt = input + 缓存写 + 缓存读，明细随行） */
        public Usage toUsage() {
            int cacheCreation = cacheCreationInputTokens == null ? 0 : cacheCreationInputTokens;
            int cacheRead = cacheReadInputTokens == null ? 0 : cacheReadInputTokens;
            return Usage.of(inputTokens + cacheCreation + cacheRead, outputTokens, cacheRead, cacheCreation);
        }
    }
}
