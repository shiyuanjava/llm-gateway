package com.llm.gateway.resilience;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.llm.gateway.config.GatewayProperties;

/**
 * 熔断器注册表：每个供应商一个熔断器，按需创建并复用。
 */
@Component
public class CircuitBreakerRegistry {

    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long openMillis;

    /**
     * @param properties 网关配置，提供熔断阈值与冷却时长
     */
    public CircuitBreakerRegistry(GatewayProperties properties) {
        GatewayProperties.Resilience.CircuitBreakerConfig config =
                properties.resilience().circuitBreaker();
        this.failureThreshold = config.failureThreshold();
        this.openMillis = config.openSeconds() * 1000L;
    }

    /**
     * 取指定供应商的熔断器，不存在则创建。
     *
     * @param provider 供应商名
     * @return 熔断器
     */
    public CircuitBreaker get(String provider) {
        return breakers.computeIfAbsent(provider, p -> new CircuitBreaker(p, failureThreshold, openMillis));
    }
}
