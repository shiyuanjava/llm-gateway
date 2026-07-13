package com.llm.gateway.cache;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;

/**
 * 基于内存的精确缓存，带 TTL 过期（惰性清理）。
 *
 * <p>对「同样问题被反复问」的场景（FAQ、固定 prompt 抽取）能直接省下供应商调用成本。
 * 多实例/跨重启场景配置 {@code gateway.cache.store=redis} 切换到 {@code RedisResponseCache}。
 */
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "memory", matchIfMissing = true)
public class ExactMatchCache implements ResponseCache {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;

    /**
     * @param properties 网关配置，提供 TTL
     */
    public ExactMatchCache(GatewayProperties properties) {
        this.ttlMillis = properties.cache().ttlSeconds() * 1000L;
    }

    @Override
    public Optional<ChatCompletionResponse> get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt < clock()) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.response);
    }

    @Override
    public void put(String key, ChatCompletionResponse response) {
        store.put(key, new Entry(response, clock() + ttlMillis));
    }

    /**
     * 当前时间（毫秒）。抽成方法便于测试覆写。
     *
     * @return 当前毫秒时间戳
     */
    protected long clock() {
        return System.currentTimeMillis();
    }

    /**
     * 缓存条目：响应 + 过期时刻。
     */
    private record Entry(ChatCompletionResponse response, long expiresAt) {
    }
}
