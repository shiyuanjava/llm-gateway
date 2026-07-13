package com.llm.gateway;

import java.util.List;
import java.util.Map;

import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.config.GatewayProperties.Cache;
import com.llm.gateway.config.GatewayProperties.Guardrail;
import com.llm.gateway.config.GatewayProperties.Llm;
import com.llm.gateway.config.GatewayProperties.ProviderConfig;
import com.llm.gateway.config.GatewayProperties.Quota;
import com.llm.gateway.config.GatewayProperties.RateLimit;
import com.llm.gateway.config.GatewayProperties.Resilience;
import com.llm.gateway.config.GatewayProperties.Resilience.CircuitBreakerConfig;
import com.llm.gateway.config.GatewayProperties.Routing;

/**
 * 单元测试公共夹具：构造各组件所需的 {@link GatewayProperties}（不含已迁移到数据库的部分）。
 */
public final class Fixtures {

    private Fixtures() {
    }

    /** @return 一份默认配置 */
    public static GatewayProperties properties() {
        return properties(60, 300, 1_000_000L, 5, 30, 2);
    }

    /**
     * 构造可定制关键参数的配置。
     *
     * @param requestsPerMinute 限流速率
     * @param cacheTtlSeconds   缓存 TTL（秒）
     * @param tokensPerTenant   租户 Token 配额
     * @param cbThreshold       熔断失败阈值
     * @param cbOpenSeconds     熔断冷却秒数
     * @param maxRetries        最大重试次数
     * @return 配置
     */
    public static GatewayProperties properties(int requestsPerMinute, long cacheTtlSeconds, long tokensPerTenant,
                                               int cbThreshold, int cbOpenSeconds, int maxRetries) {
        return new GatewayProperties(
                new Routing("deepseek-v4-pro"),
                new Llm("deepseek", "deepseek-v4-pro"),
                Map.of("mock", new ProviderConfig("", "")),
                new RateLimit(requestsPerMinute),
                new Quota(tokensPerTenant),
                new Cache(true, cacheTtlSeconds, new Cache.Semantic(false, 0.92)),
                new Guardrail(List.of("制造炸弹")),
                new Resilience(maxRetries, new CircuitBreakerConfig(cbThreshold, cbOpenSeconds)),
                new GatewayProperties.Http(5000, 30000));
    }
}
