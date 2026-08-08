package com.llm.gateway.cache;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.config.GatewayProperties.Cache;

import static com.llm.gateway.Fixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存的租户隔离口径：默认按租户分区，显式配置 global 才跨租户共享。
 */
class CacheTenantIsolationTest {

    private static final ChatCompletionRequest REQUEST =
            new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("同一个问题")), null, null, null, null, null);

    @Test
    void exactCacheKeyDiffersPerTenant() {
        assertThat(CacheKey.of(REQUEST, "tenant-a")).isNotEqualTo(CacheKey.of(REQUEST, "tenant-b"));
        assertThat(CacheKey.of(REQUEST, "tenant-a")).isEqualTo(CacheKey.of(REQUEST, "tenant-a"));
    }

    @Test
    void tenantScopedCacheDoesNotServeAnotherTenant() {
        CacheService service = cacheService("tenant", false);
        service.store(REQUEST, response("gpt-4o"), "tenant-a");

        assertThat(service.lookup(REQUEST, "tenant-b")).isEmpty();
        assertThat(service.lookup(REQUEST, "tenant-a")).isPresent();
    }

    @Test
    void globalScopeStillSharesAcrossTenants() {
        CacheService service = cacheService("global", false);
        service.store(REQUEST, response("gpt-4o"), "tenant-a");

        assertThat(service.lookup(REQUEST, "tenant-b")).isPresent();
    }

    @Test
    void semanticCacheDoesNotCrossTenants() {
        CacheService service = cacheService("tenant", true);
        service.store(REQUEST, response("gpt-4o"), "tenant-a");

        ChatCompletionRequest reworded =
                new ChatCompletionRequest("gpt-4o", List.of(ChatMessage.user("同一个问题?")), null, null, null, null, null);
        assertThat(service.lookup(reworded, "tenant-b")).isEmpty();
    }

    @Test
    void semanticCacheDoesNotServeAnotherModel() {
        CacheService service = cacheService("tenant", true);
        service.store(REQUEST, response("gpt-4o"), "tenant-a");

        ChatCompletionRequest otherModel = new ChatCompletionRequest(
                "claude-opus-4-8", List.of(ChatMessage.user("同一个问题?")), null, null, null, null, null);
        assertThat(service.lookup(otherModel, "tenant-a")).isEmpty();
    }

    private CacheService cacheService(String scope, boolean semanticEnabled) {
        GatewayProperties base = properties(60, 300, 1_000_000, 3, 10, 2);
        GatewayProperties properties = new GatewayProperties(
                base.routing(),
                base.llm(),
                base.providers(),
                base.rateLimit(),
                base.quota(),
                new Cache(true, "memory", 300, scope, new Cache.Semantic(semanticEnabled, 0.92)),
                base.guardrail(),
                base.resilience(),
                base.http());
        return new CacheService(
                new ExactMatchCache(properties), new SemanticCache(new MockEmbedder(), properties), properties);
    }

    private ChatCompletionResponse response(String model) {
        return ChatCompletionResponse.singleMessage("id-1", 1L, model, "答案", "stop", Usage.of(1, 1));
    }
}
