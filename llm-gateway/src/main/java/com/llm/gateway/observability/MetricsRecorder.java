package com.llm.gateway.observability;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 指标记录器：把网关的关键运行指标上报到 Micrometer，可经 Actuator 的 {@code /actuator/prometheus}
 * 端点被 Prometheus 抓取。
 *
 * <p>统一的可观测入口让「请求量、缓存命中率、Token 消耗、成本、错误、延迟」都有据可查，
 * 对应 LLM Gateway 的可观测与成本归因能力。
 */
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;

    /**
     * @param registry Micrometer 指标注册表（Spring 自动配置）
     */
    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 记录一次请求。
     *
     * @param tenant 租户
     * @param model  请求的模型/别名
     */
    public void incRequest(String tenant, String model) {
        registry.counter("llm.gateway.requests", "tenant", tenant, "model", model)
                .increment();
    }

    /** 记录一次缓存命中。 */
    public void incCacheHit() {
        registry.counter("llm.gateway.cache.hit").increment();
    }

    /**
     * 记录一次错误。
     *
     * @param code 错误码
     */
    public void incError(String code) {
        registry.counter("llm.gateway.errors", "code", code).increment();
    }

    /**
     * 累加 Token 消耗。
     *
     * @param model       产出响应的模型
     * @param totalTokens 本次总 Token 数
     */
    public void incTokens(String model, long totalTokens) {
        registry.counter("llm.gateway.tokens", "model", model).increment(totalTokens);
    }

    /**
     * 累加成本。
     *
     * @param model 产出响应的模型
     * @param cost  本次成本（美元）
     */
    public void recordCost(String model, double cost) {
        registry.counter("llm.gateway.cost.usd", "model", model).increment(cost);
    }

    /**
     * 记录端到端延迟。
     *
     * @param millis 毫秒数
     */
    public void recordLatency(long millis) {
        registry.timer("llm.gateway.latency").record(Duration.ofMillis(millis));
    }

    /** 记录一次流式请求。 */
    public void incStreamRequest() {
        registry.counter("llm.gateway.stream.requests").increment();
    }

    /**
     * 记录首 Token 延迟（TTFT）：请求开始到第一帧写出的耗时，是流式体验的核心指标。
     *
     * @param millis 毫秒数
     */
    public void recordTtft(long millis) {
        registry.timer("llm.gateway.ttft").record(Duration.ofMillis(millis));
    }
}
