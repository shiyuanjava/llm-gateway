package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token 用量统计（OpenAI 协议）。
 *
 * <p><strong>口径钉死</strong>：{@code promptTokens} 恒为「完整输入」（<strong>含</strong>缓存，
 * OpenAI 语义）；两个缓存字段是它的内部拆分（约定 {@code cacheRead + cacheCreation ≤ prompt}，
 * 不强制校验），仅供计费与审计使用，「只进不出」——从上游 JSON 可读入（{@link #fromJson}），
 * 序列化永不输出（{@link JsonIgnore}），下游协议始终保持三字段。
 *
 * <p>Anthropic 的 input_tokens 不含缓存，由其适配器做加法归一化后再构造本对象。
 *
 * @param promptTokens        输入 Token 数（含缓存）
 * @param completionTokens    输出 Token 数
 * @param totalTokens         合计 Token 数
 * @param cacheReadTokens     缓存读 Token 数（内部拆分，不序列化）
 * @param cacheCreationTokens 缓存写 Token 数（内部拆分，不序列化）
 */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        @JsonIgnore int cacheReadTokens,
        @JsonIgnore int cacheCreationTokens) {

    /** OpenAI 的 {@code prompt_tokens_details}（仅取缓存命中数，其余字段忽略）。 */
    public record PromptTokensDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {}

    /**
     * 反序列化入口：兼容 OpenAI 把缓存命中数嵌在 {@code prompt_tokens_details.cached_tokens} 的形态。
     * OpenAI 自动缓存无「缓存写」概念，cacheCreationTokens 恒 0（Anthropic 走适配器代码构造，不经此处）。
     *
     * <p>容错：部分兼容网关的 usage 帧缺 {@code total_tokens}（Jackson 3 默认
     * FAIL_ON_NULL_FOR_PRIMITIVES 会因此打挂整条流），故该参数收 {@link Integer}，
     * 缺失时回退 {@code prompt + completion}——补缺不是重算，上游给了值就原样保留，
     * 哪怕与 p+c 不一致。prompt/completion 保持 int 严格（缺失即坏帧，理应失败）。
     */
    @JsonCreator
    public static Usage fromJson(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") Integer totalTokens,
            @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails) {
        int cacheRead = promptTokensDetails == null || promptTokensDetails.cachedTokens() == null
                ? 0
                : promptTokensDetails.cachedTokens();
        int total = totalTokens == null ? promptTokens + completionTokens : totalTokens;
        return new Usage(promptTokens, completionTokens, total, cacheRead, 0);
    }

    /**
     * 无缓存拆分的用量（既有调用点全部走这里，行为不变）。
     *
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return 用量对象
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return of(promptTokens, completionTokens, 0, 0);
    }

    /**
     * 带缓存拆分的用量，自动求和 total。
     *
     * @param promptTokens        输入 Token 数（含缓存）
     * @param completionTokens    输出 Token 数
     * @param cacheReadTokens     缓存读 Token 数
     * @param cacheCreationTokens 缓存写 Token 数
     * @return 用量对象
     */
    public static Usage of(int promptTokens, int completionTokens, int cacheReadTokens, int cacheCreationTokens) {
        return new Usage(
                promptTokens, completionTokens, promptTokens + completionTokens, cacheReadTokens, cacheCreationTokens);
    }
}
