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

    @Test
    void nonRetryableErrorSkipsRetryAndBreakerButFallsBack() {
        // maxRetries=2：不可重试错误既不该重试（只调用 1 次），也不该计入熔断失败
        ResilientExecutor retryExecutor = new ResilientExecutor(
                registry, Fixtures.properties(60, 300, 1_000_000L, 2, 30, 2));
        RouteDecision decision =
                new RouteDecision(new ProviderTarget("p-4xx", "m"), List.of(new ProviderTarget("p-ok2", "m")));

        int[] calls = {0};
        ChatCompletionResponse response = retryExecutor.execute(decision, target -> {
            if ("p-4xx".equals(target.provider())) {
                calls[0]++;
                throw new ProviderException("HTTP 400", null, false);
            }
            return ok();
        });

        assertEquals("served-ok", response.id());
        assertEquals(1, calls[0], "不可重试错误不应重试");
        assertEquals(CircuitBreaker.State.CLOSED, registry.get("p-4xx").state());
        // 熔断阈值=2：若误计入失败，再来一次同样的确定性错误就会打开；验证仍保持关闭
        retryExecutor.execute(decision, target -> {
            if ("p-4xx".equals(target.provider())) {
                throw new ProviderException("HTTP 400", null, false);
            }
            return ok();
        });
        assertEquals(CircuitBreaker.State.CLOSED, registry.get("p-4xx").state());
    }

    @Test
    void retryableStatusClassification() {
        assertTrue(ProviderException.isRetryableStatus(500));
        assertTrue(ProviderException.isRetryableStatus(429));
        assertTrue(ProviderException.isRetryableStatus(408));
        org.junit.jupiter.api.Assertions.assertFalse(ProviderException.isRetryableStatus(400));
        org.junit.jupiter.api.Assertions.assertFalse(ProviderException.isRetryableStatus(401));
        org.junit.jupiter.api.Assertions.assertFalse(ProviderException.isRetryableStatus(422));
    }

    private ChatCompletionResponse ok() {
        return ChatCompletionResponse.singleMessage("served-ok", 0, "m", "hi", "stop", Usage.of(1, 1));
    }
}
