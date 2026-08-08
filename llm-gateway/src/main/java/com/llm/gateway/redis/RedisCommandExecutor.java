package com.llm.gateway.redis;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/** Executes Redis commands through the role-specific circuit and metrics boundary. */
@Component
public class RedisCommandExecutor {

    public static class RedisCircuitOpenException extends RuntimeException {
        private final RedisRole role;

        RedisCircuitOpenException(RedisRole role) {
            super("Redis " + role.tag() + " circuit open");
            this.role = role;
        }

        public RedisRole role() {
            return role;
        }
    }

    public static class RedisCommandException extends RuntimeException {
        private final RedisRole role;

        RedisCommandException(RedisRole role, Throwable cause) {
            super("Redis " + role.tag() + " command failed", cause);
            this.role = role;
        }

        public RedisRole role() {
            return role;
        }
    }

    private final RedisAvailabilityCircuit circuit;
    private final RedisCommandMetrics metrics;

    public RedisCommandExecutor(RedisAvailabilityCircuit circuit, RedisCommandMetrics metrics) {
        this.circuit = circuit;
        this.metrics = metrics;
    }

    public <T> T execute(RedisRole role, String domain, String operation, Supplier<T> command) {
        if (!circuit.tryAcquire(role)) {
            recordShortCircuited(role, domain, operation);
            throw new RedisCircuitOpenException(role);
        }
        long start = System.nanoTime();
        try {
            T result = command.get();
            circuit.onSuccess(role);
            recordCompleted(role, domain, operation, "success", System.nanoTime() - start);
            return result;
        } catch (RuntimeException failure) {
            circuit.onFailure(role);
            recordCompleted(role, domain, operation, "failure", System.nanoTime() - start);
            throw new RedisCommandException(role, failure);
        }
    }

    private void recordCompleted(RedisRole role, String domain, String operation, String outcome, long elapsedNanos) {
        try {
            metrics.completed(role, domain, operation, outcome, elapsedNanos);
        } catch (RuntimeException ignored) {
            // Observability must never change Redis command semantics or circuit state.
        }
    }

    private void recordShortCircuited(RedisRole role, String domain, String operation) {
        try {
            metrics.shortCircuited(role, domain, operation);
        } catch (RuntimeException ignored) {
            // Observability must never mask the domain-visible circuit-open signal.
        }
    }
}
