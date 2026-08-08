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
 *
 * <p><strong>隔离口径</strong>：默认 {@code gateway.cache.scope=tenant}，租户参与缓存键，
 * 一个租户的响应不会被另一个租户读到；只有显式配置 {@code global} 才跨租户共享。
 * 语义缓存按「租户 + 请求模型」分区——它按相似度命中，跨租户会把 A 的原文回给 B，
 * 跨模型则等于绕过 {@code allowedModels} 白名单。
 */
@Service
public class CacheService {

    private final ResponseCache exactCache;
    private final SemanticCache semanticCache;
    private final boolean enabled;
    private final boolean semanticEnabled;
    private final boolean sharedAcrossTenants;

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
        this.sharedAcrossTenants = properties.cache().sharedAcrossTenants();
    }

    /**
     * 查缓存：先精确后语义。
     *
     * @param request 请求
     * @param tenant  发起请求的租户
     * @return 命中则返回缓存响应，否则为空
     */
    public Optional<ChatCompletionResponse> lookup(ChatCompletionRequest request, String tenant) {
        if (!enabled) {
            return Optional.empty();
        }
        Optional<ChatCompletionResponse> exact = exactCache.get(CacheKey.of(request, scopeOf(tenant)));
        if (exact.isPresent()) {
            return exact;
        }
        if (semanticEnabled) {
            return semanticCache.lookup(scopeOf(tenant), request.model(), lastUserText(request));
        }
        return Optional.empty();
    }

    /**
     * 写缓存：同时写精确缓存与（如启用）语义缓存。
     *
     * @param request  请求
     * @param response 响应
     * @param tenant   发起请求的租户
     */
    public void store(ChatCompletionRequest request, ChatCompletionResponse response, String tenant) {
        if (!enabled) {
            return;
        }
        exactCache.put(CacheKey.of(request, scopeOf(tenant)), response);
        if (semanticEnabled) {
            semanticCache.put(scopeOf(tenant), request.model(), lastUserText(request), response);
        }
    }

    /** 共享口径返回 null（全局分区），否则返回租户标识作为分区键。 */
    private String scopeOf(String tenant) {
        return sharedAcrossTenants ? null : tenant;
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
