package com.llm.gateway.cache;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.config.GatewayProperties;

/**
 * 缓存编排服务：对外提供统一的查/写入口，内部先查精确缓存，未命中且开启语义缓存时再查语义缓存。
 *
 * <p>把「多层缓存协同」收敛到一个服务里，让核心编排逻辑 {@code GatewayService} 保持简洁，
 * 也便于后续接入更多缓存层。
 */
@Service
public class CacheService {

    private final ResponseCache exactCache;
    private final SemanticCache semanticCache;
    private final boolean enabled;
    private final boolean semanticEnabled;

    /**
     * @param exactCache    精确缓存
     * @param semanticCache 语义缓存
     * @param properties    网关配置，提供缓存开关
     */
    public CacheService(ResponseCache exactCache, SemanticCache semanticCache, GatewayProperties properties) {
        this.exactCache = exactCache;
        this.semanticCache = semanticCache;
        this.enabled = properties.cache().enabled();
        this.semanticEnabled = properties.cache().semantic().enabled();
    }

    /**
     * 查缓存：先精确后语义。
     *
     * @param request 请求
     * @return 命中则返回缓存响应，否则为空
     */
    public Optional<ChatCompletionResponse> lookup(ChatCompletionRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        Optional<ChatCompletionResponse> exact = exactCache.get(CacheKey.of(request));
        if (exact.isPresent()) {
            return exact;
        }
        if (semanticEnabled) {
            return semanticCache.lookup(lastUserText(request));
        }
        return Optional.empty();
    }

    /**
     * 写缓存：同时写精确缓存与（如启用）语义缓存。
     *
     * @param request  请求
     * @param response 响应
     */
    public void store(ChatCompletionRequest request, ChatCompletionResponse response) {
        if (!enabled) {
            return;
        }
        exactCache.put(CacheKey.of(request), response);
        if (semanticEnabled) {
            semanticCache.put(lastUserText(request), response);
        }
    }

    /**
     * 取最后一条 user 消息文本（语义缓存以它为查询）。
     *
     * @param request 请求
     * @return 最后一条用户消息文本，无则空串
     */
    private String lastUserText(ChatCompletionRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if ("user".equals(message.role())) {
                return message.content() == null ? "" : message.content();
            }
        }
        return "";
    }
}
