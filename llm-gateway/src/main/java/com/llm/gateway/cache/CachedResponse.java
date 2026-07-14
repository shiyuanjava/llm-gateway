package com.llm.gateway.cache;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

/**
 * Redis 缓存的存储信封。
 *
 * <p>{@link Usage} 的缓存拆分字段是 {@code @JsonIgnore}（对外协议只出三字段），响应直接 JSON
 * 往返会丢拆分。信封把两个拆分值平铺存在响应旁边，读取时重建——对外序列化行为零改动。
 *
 * @param response            响应（正常 Jackson 序列化，usage 只出三字段）
 * @param cacheReadTokens     从 usage 摘出的缓存读拆分
 * @param cacheCreationTokens 从 usage 摘出的缓存写拆分
 */
public record CachedResponse(ChatCompletionResponse response, int cacheReadTokens, int cacheCreationTokens) {

    /**
     * 包装响应，摘出 Usage 拆分。
     *
     * @param response 待缓存的响应
     * @return 信封
     */
    public static CachedResponse of(ChatCompletionResponse response) {
        Usage usage = response.usage();
        return new CachedResponse(
                response, usage == null ? 0 : usage.cacheReadTokens(), usage == null ? 0 : usage.cacheCreationTokens());
    }

    /**
     * 还原响应：有拆分则用信封值重建 Usage（total 原样保留，不重算）。
     *
     * @return 还原后的响应
     */
    public ChatCompletionResponse toResponse() {
        Usage usage = response.usage();
        if (usage == null || (cacheReadTokens == 0 && cacheCreationTokens == 0)) {
            return response;
        }
        Usage rebuilt = new Usage(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens(),
                cacheReadTokens,
                cacheCreationTokens);
        return new ChatCompletionResponse(
                response.id(), response.object(), response.created(), response.model(), response.choices(), rebuilt);
    }
}
