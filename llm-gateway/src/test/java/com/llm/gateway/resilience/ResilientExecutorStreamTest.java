package com.llm.gateway.resilience;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.router.RouteDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResilientExecutorStreamTest {

    /** maxRetries=0、熔断阈值=2，便于精确观察行为。 */
    private final CircuitBreakerRegistry registry =
            new CircuitBreakerRegistry(Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));

    private final ResilientExecutor executor =
            new ResilientExecutor(registry, Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));

    @Test
    void fallsBackToNextTargetBeforeFirstFrame() {
        RouteDecision decision =
                new RouteDecision(new ProviderTarget("s-fail", "m"), List.of(new ProviderTarget("s-ok", "m")));

        Usage usage = executor.executeStream(
                decision,
                target -> {
                    if ("s-fail".equals(target.provider())) {
                        throw new ProviderException("连接失败");
                    }
                    return Usage.of(1, 2);
                },
                () -> false); // 首帧未发出

        assertEquals(3, usage.totalTokens());
    }

    @Test
    void rethrowsImmediatelyAfterFirstFrame() {
        RouteDecision decision =
                new RouteDecision(new ProviderTarget("mid-fail", "m"), List.of(new ProviderTarget("never", "m")));
        AtomicBoolean invokedFallback = new AtomicBoolean(false);

        assertThrows(
                ProviderException.class,
                () -> executor.executeStream(
                        decision,
                        target -> {
                            if ("never".equals(target.provider())) {
                                invokedFallback.set(true);
                            }
                            throw new ProviderException("流中途断");
                        },
                        () -> true)); // 首帧已发出

        assertEquals(false, invokedFallback.get(), "首帧后不得再尝试降级目标");
    }

    @Test
    void clientDisconnectDoesNotTripBreakerNorRetry() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("cd", "m"), List.of());

        assertThrows(
                ClientDisconnectedException.class,
                () -> executor.executeStream(
                        decision,
                        target -> {
                            throw new ClientDisconnectedException("gone", null);
                        },
                        () -> true));

        assertEquals(CircuitBreaker.State.CLOSED, registry.get("cd").state(), "客户端断开不是供应商故障，熔断计数不得增加");
    }

    @Test
    void guardrailAbortPropagatesWithoutBreakerPenalty() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("gr", "m"), List.of());

        assertThrows(
                GuardrailException.class,
                () -> executor.executeStream(
                        decision,
                        target -> {
                            throw new GuardrailException("出站命中");
                        },
                        () -> true));

        assertEquals(CircuitBreaker.State.CLOSED, registry.get("gr").state());
    }

    @Test
    void postFirstFrameFailureStillFeedsBreaker() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("late-fail", "m"), List.of());

        // 熔断阈值=2：首帧后失败虽不重试/降级，但仍计入熔断，两次后应打开
        for (int i = 0; i < 2; i++) {
            assertThrows(
                    ProviderException.class,
                    () -> executor.executeStream(
                            decision,
                            target -> {
                                throw new ProviderException("流中途断");
                            },
                            () -> true));
        }

        assertEquals(CircuitBreaker.State.OPEN, registry.get("late-fail").state(), "首帧后失败依然是供应商故障，必须计入熔断");
    }

    @Test
    void retriesSameTargetBeforeFirstFrame() {
        // maxRetries=1：同目标失败一次后应在原目标上重试成功
        ResilientExecutor retryExecutor = new ResilientExecutor(
                new CircuitBreakerRegistry(Fixtures.properties(60, 300, 1_000_000L, 2, 30, 1)),
                Fixtures.properties(60, 300, 1_000_000L, 2, 30, 1));
        RouteDecision decision = new RouteDecision(new ProviderTarget("flaky-once", "m"), List.of());
        AtomicInteger invocations = new AtomicInteger();

        Usage usage = retryExecutor.executeStream(
                decision,
                target -> {
                    if (invocations.incrementAndGet() == 1) {
                        throw new ProviderException("第一次失败");
                    }
                    return Usage.of(1, 2);
                },
                () -> false);

        assertEquals(3, usage.totalTokens());
        assertEquals(2, invocations.get(), "应在同目标上恰好调用两次（首次 + 一次重试）");
    }

    @Test
    void stopsFallingBackOnceFirstFrameWritten() {
        // 首帧标志在降级过程中由 false 翻转为 true：目标 1 首帧前失败可降级，
        // 目标 2 写出首帧后失败则只能断流，目标 3 不得再被尝试
        RouteDecision decision = new RouteDecision(
                new ProviderTarget("t1", "m"), List.of(new ProviderTarget("t2", "m"), new ProviderTarget("t3", "m")));
        AtomicBoolean firstFrameWritten = new AtomicBoolean(false);
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(
                ProviderException.class,
                () -> executor.executeStream(
                        decision,
                        target -> {
                            invocations.incrementAndGet();
                            if ("t2".equals(target.provider())) {
                                firstFrameWritten.set(true); // 模拟首帧已写给客户端
                            }
                            throw new ProviderException(target.provider() + " 失败");
                        },
                        firstFrameWritten::get));

        assertEquals(2, invocations.get(), "首帧写出后不得再尝试第三个目标");
    }
}
