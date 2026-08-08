package com.llm.gateway.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCommandExecutorTest {

    @Test
    void wrapsFailureAndDoesNotInvokeSupplierWhenCircuitIsOpen() {
        RedisAvailabilityCircuit circuit = new RedisAvailabilityCircuit(1, Duration.ofSeconds(2), System::nanoTime);
        RedisCommandExecutor executor =
                new RedisCommandExecutor(circuit, new RedisCommandMetrics(new SimpleMeterRegistry()));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> executor.execute(RedisRole.CONTROL, "test", "get", () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("down");
                }))
                .isInstanceOf(RedisCommandExecutor.RedisCommandException.class);
        assertThatThrownBy(() -> executor.execute(RedisRole.CONTROL, "test", "get", () -> {
                    calls.incrementAndGet();
                    return "unexpected";
                }))
                .isInstanceOf(RedisCommandExecutor.RedisCircuitOpenException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void shortCircuitMetricFailureDoesNotMaskCircuitState() {
        RedisAvailabilityCircuit circuit = new RedisAvailabilityCircuit(1, Duration.ofSeconds(2), System::nanoTime);
        RedisCommandMetrics metrics = new RedisCommandMetrics(new SimpleMeterRegistry()) {
            @Override
            public void shortCircuited(RedisRole role, String domain, String operation) {
                throw new IllegalStateException("metrics unavailable");
            }
        };
        RedisCommandExecutor executor = new RedisCommandExecutor(circuit, metrics);

        assertThatThrownBy(() -> executor.execute(RedisRole.CACHE, "test", "get", () -> {
                    throw new IllegalStateException("redis unavailable");
                }))
                .isInstanceOf(RedisCommandExecutor.RedisCommandException.class);

        assertThatThrownBy(() -> executor.execute(RedisRole.CACHE, "test", "get", () -> "unexpected"))
                .isInstanceOf(RedisCommandExecutor.RedisCircuitOpenException.class);
    }
}
