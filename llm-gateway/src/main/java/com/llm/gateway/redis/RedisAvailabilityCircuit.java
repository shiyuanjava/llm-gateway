package com.llm.gateway.redis;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Small per-role circuit breaker used to avoid repeatedly waiting on an unavailable Redis. */
@Component
public class RedisAvailabilityCircuit {

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoTime;
    private final Map<RedisRole, State> states = new EnumMap<>(RedisRole.class);

    @Autowired
    public RedisAvailabilityCircuit(GatewayRedisProperties properties) {
        this(
                properties.getCircuit().getFailureThreshold(),
                properties.getCircuit().getOpenDuration(),
                System::nanoTime);
    }

    RedisAvailabilityCircuit(int failureThreshold, Duration openDuration, LongSupplier nanoTime) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("Redis circuit failureThreshold 必须大于 0");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Redis circuit openDuration 必须大于 0");
        }
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoTime = nanoTime;
        for (RedisRole role : RedisRole.values()) {
            states.put(role, new State());
        }
    }

    public boolean tryAcquire(RedisRole role) {
        return states.get(role).tryAcquire(nanoTime.getAsLong());
    }

    public void onSuccess(RedisRole role) {
        states.get(role).success();
    }

    public void onFailure(RedisRole role) {
        states.get(role).failure(nanoTime.getAsLong());
    }

    private final class State {
        private int consecutiveFailures;
        private long openUntilNanos;
        private boolean probeInFlight;

        synchronized boolean tryAcquire(long now) {
            if (openUntilNanos == 0L) {
                return true;
            }
            if (now < openUntilNanos) {
                return false;
            }
            if (probeInFlight) {
                return false;
            }
            probeInFlight = true;
            return true;
        }

        synchronized void success() {
            consecutiveFailures = 0;
            openUntilNanos = 0L;
            probeInFlight = false;
        }

        synchronized void failure(long now) {
            consecutiveFailures++;
            if (probeInFlight || consecutiveFailures >= failureThreshold) {
                openUntilNanos = now + openNanos;
            }
            probeInFlight = false;
        }
    }
}
