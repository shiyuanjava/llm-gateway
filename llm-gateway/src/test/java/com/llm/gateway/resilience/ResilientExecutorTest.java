package com.llm.gateway.resilience;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.NoProviderAvailableException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.router.RouteDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilientExecutorTest {

    /** maxRetries=0、熔断阈值=2，便于精确观察行为。 */
    private final CircuitBreakerRegistry registry =
            new CircuitBreakerRegistry(Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));

    private final ResilientExecutor executor =
            new ResilientExecutor(registry, Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));

    @Test
    void shouldFallBackToNextTargetWhenPrimaryFails() {
        RouteDecision decision =
                new RouteDecision(new ProviderTarget("p-fail", "m"), List.of(new ProviderTarget("p-ok", "m")));

        ChatCompletionResponse response = executor.execute(decision, target -> {
            if ("p-fail".equals(target.provider())) {
                throw new ProviderException("boom");
            }
            return ok();
        });

        assertEquals("served-ok", response.id());
    }

    @Test
    void shouldThrowWhenAllTargetsFail() {
        RouteDecision decision =
                new RouteDecision(new ProviderTarget("p1", "m"), List.of(new ProviderTarget("p2", "m")));

        assertThrows(
                NoProviderAvailableException.class,
                () -> executor.execute(decision, target -> {
                    throw new ProviderException("always fail");
                }));
    }

    @Test
    void shouldOpenCircuitAfterReachingFailureThreshold() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("flaky", "m"), List.of());

        // 两次失败（阈值=2）后熔断器应打开
        for (int i = 0; i < 2; i++) {
            assertThrows(
                    NoProviderAvailableException.class,
                    () -> executor.execute(decision, t -> {
                        throw new ProviderException("fail");
                    }));
        }

        assertEquals(CircuitBreaker.State.OPEN, registry.get("flaky").state());
    }

    @Test
    void retriesBackOffBetweenAttempts() {
        // maxRetries=2：失败 3 次，前两次失败后应分别退避 100ms、200ms，总耗时 ≥ 300ms
        ResilientExecutor backoffExecutor = new ResilientExecutor(
                new CircuitBreakerRegistry(Fixtures.properties()), Fixtures.properties(60, 300, 1_000_000L, 5, 30, 2));
        RouteDecision decision = new RouteDecision(new ProviderTarget("mock", "m1"), List.of());

        long start = System.nanoTime();
        assertThrows(
                NoProviderAvailableException.class,
                () -> backoffExecutor.execute(decision, target -> {
                    throw new ProviderException("boom");
                }));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 300, "预期总耗时 ≥ 300ms，实际 " + elapsedMs + "ms");
    }

    private ChatCompletionResponse ok() {
        return ChatCompletionResponse.singleMessage("served-ok", 0, "m", "hi", "stop", Usage.of(1, 1));
    }
}
