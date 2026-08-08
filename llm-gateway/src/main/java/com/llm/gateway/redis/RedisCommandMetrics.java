package com.llm.gateway.redis;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/** Low-cardinality Redis command metrics. Sensitive identifiers never become metric tags. */
@Component
public class RedisCommandMetrics {

    private final MeterRegistry registry;

    public RedisCommandMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void completed(RedisRole role, String domain, String operation, String outcome, long elapsedNanos) {
        registry.timer(
                        "llm.gateway.redis.command",
                        "role",
                        role.tag(),
                        "domain",
                        domain,
                        "operation",
                        operation,
                        "outcome",
                        outcome)
                .record(Duration.ofNanos(elapsedNanos));
    }

    public void shortCircuited(RedisRole role, String domain, String operation) {
        registry.counter(
                        "llm.gateway.redis.short_circuit", "role", role.tag(), "domain", domain, "operation", operation)
                .increment();
    }

    public void cacheValueOversize() {
        registry.counter("llm.gateway.cache.value_oversize").increment();
    }
}
