package com.llm.gateway.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RedisAvailabilityCircuitTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(GatewayRedisProperties.class, GatewayRedisProperties::new)
            .withUserConfiguration(RedisAvailabilityCircuit.class);

    @Test
    void springUsesThePropertiesConstructor() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(RedisAvailabilityCircuit.class));
    }

    @Test
    void opensPerRoleAndAllowsOnlyOneHalfOpenProbe() {
        AtomicLong now = new AtomicLong();
        RedisAvailabilityCircuit circuit = new RedisAvailabilityCircuit(3, Duration.ofSeconds(2), now::get);

        circuit.onFailure(RedisRole.CACHE);
        circuit.onFailure(RedisRole.CACHE);
        circuit.onFailure(RedisRole.CACHE);
        assertThat(circuit.tryAcquire(RedisRole.CACHE)).isFalse();
        assertThat(circuit.tryAcquire(RedisRole.CONTROL)).isTrue();

        now.set(Duration.ofSeconds(2).toNanos());
        assertThat(circuit.tryAcquire(RedisRole.CACHE)).isTrue();
        assertThat(circuit.tryAcquire(RedisRole.CACHE)).isFalse();
        circuit.onSuccess(RedisRole.CACHE);
        assertThat(circuit.tryAcquire(RedisRole.CACHE)).isTrue();
    }
}
