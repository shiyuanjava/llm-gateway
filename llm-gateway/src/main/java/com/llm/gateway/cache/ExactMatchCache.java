package com.llm.gateway.cache;

import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;

/**
 * 基于 Caffeine 的内存精确缓存：TTL 过期 + 最大条数上限（W-TinyLFU 淘汰），
 * 冷条目不再依赖「被再次访问」才清理，键基数大时也不会无界增长。
 *
 * <p>对「同样问题被反复问」的场景（FAQ、固定 prompt 抽取）能直接省下供应商调用成本。
 * 多实例/跨重启场景配置 {@code gateway.cache.store=redis} 切换到 {@link RedisResponseCache}。
 */
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "memory", matchIfMissing = true)
public class ExactMatchCache implements ResponseCache {

    /** 最大缓存条数:响应体可达数十 KB,1 万条约数百 MB,足够 FAQ 场景又不至 OOM。 */
    private static final long MAX_ENTRIES = 10_000;

    private final Cache<String, ChatCompletionResponse> store;

    /**
     * @param properties 网关配置，提供 TTL
     */
    public ExactMatchCache(GatewayProperties properties) {
        this.store = Caffeine.newBuilder()
                .maximumSize(MAX_ENTRIES)
                .expireAfterWrite(Duration.ofSeconds(properties.cache().ttlSeconds()))
                // 用可覆写的 clock() 驱动过期判定，便于测试控制时间
                .ticker(() -> clock() * 1_000_000L)
                .build();
    }

    @Override
    public Optional<ChatCompletionResponse> get(String key) {
        return Optional.ofNullable(store.getIfPresent(key));
    }

    @Override
    public void put(String key, ChatCompletionResponse response) {
        store.put(key, response);
    }

    /**
     * 当前时间（毫秒）。抽成方法便于测试覆写。
     *
     * @return 当前毫秒时间戳
     */
    protected long clock() {
        return System.currentTimeMillis();
    }
}
