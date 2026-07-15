package com.llm.gateway.resilience;

import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.exception.NoProviderAvailableException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.observability.MetricsRecorder;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.router.RouteDecision;

/**
 * 容错执行器：把「重试 + 熔断 + Fallback」三种机制组合起来执行一次路由决策。
 *
 * <p>执行流程：沿路由链（首选 → 各降级目标）逐个尝试；每个目标先看其熔断器是否放行，放行则在
 * 最多 {@code maxRetries} 次重试内调用；成功即返回，失败则换下一个目标。整条链都失败时抛出
 * {@link NoProviderAvailableException}。这保证单个供应商的抖动或故障不会拖垮整个请求。
 */
@Component
public class ResilientExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientExecutor.class);

    private static final long BACKOFF_BASE_MS = 100;
    private static final long BACKOFF_MAX_MS = 1_000;

    private final CircuitBreakerRegistry breakerRegistry;
    private final int maxRetries;

    @Nullable
    private final MetricsRecorder metrics;

    /** 便捷构造（测试用）：不上报指标。 */
    public ResilientExecutor(CircuitBreakerRegistry breakerRegistry, GatewayProperties properties) {
        this(breakerRegistry, properties, null);
    }

    /**
     * @param breakerRegistry 熔断器注册表
     * @param properties      网关配置，提供最大重试次数
     * @param metrics         指标记录器（provider 维度重试/降级/熔断跳过/上游延迟）
     */
    @Autowired
    public ResilientExecutor(
            CircuitBreakerRegistry breakerRegistry, GatewayProperties properties, @Nullable MetricsRecorder metrics) {
        this.breakerRegistry = breakerRegistry;
        this.maxRetries = properties.resilience().maxRetries();
        this.metrics = metrics;
    }

    /**
     * 按路由决策执行调用。
     *
     * @param decision 路由决策（首选 + 降级链）
     * @param invoker  针对单个目标的实际调用逻辑
     * @return 第一个成功目标的响应
     * @throws NoProviderAvailableException 所有目标都失败或被熔断
     */
    public ChatCompletionResponse execute(RouteDecision decision, ProviderInvoker invoker) {
        Exception lastError = null;
        for (ProviderTarget target : decision.chain()) {
            CircuitBreaker breaker = breakerRegistry.get(target.provider());
            if (!breaker.allowRequest()) {
                log.warn("目标 {} 的熔断器已打开，跳过", target);
                incCircuitOpen(target);
                continue;
            }
            // 单目标内的重试（首次 + maxRetries 次重试）
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (attempt > 0) {
                        incRetry(target);
                    }
                    long startNanos = System.nanoTime();
                    ChatCompletionResponse response = invoker.invoke(target);
                    recordUpstreamLatency(target, startNanos);
                    breaker.onSuccess();
                    if (attempt > 0) {
                        log.info("目标 {} 第 {} 次重试成功", target, attempt);
                    }
                    return response;
                } catch (Exception e) {
                    lastError = e;
                    // 确定性错误（4xx/配置问题）：重试注定同样失败，不计熔断、不重试，直接换下一个目标
                    if (isNonRetryable(e)) {
                        log.warn("目标 {} 返回不可重试错误，换下一个目标：{}", target, e.getMessage());
                        break;
                    }
                    breaker.onFailure();
                    log.warn("目标 {} 调用失败（第 {} 次尝试）：{}", target, attempt + 1, e.getMessage());
                    if (attempt < maxRetries && !backoff(attempt)) {
                        throw new NoProviderAvailableException("重试等待被中断", e);
                    }
                }
            }
            // 单目标重试耗尽或确定性失败：换路由链上的下一个目标
            incFallback(target);
        }
        throw new NoProviderAvailableException("路由链上所有目标均不可用：" + decision.chain(), lastError);
    }

    /**
     * 流式版容错执行：首帧写出前，失败照常「重试 + 熔断 + 换目标」；首帧写出后响应已不可回退，
     * 异常直接上抛由调用方断流。{@link ClientDisconnectedException}（客户端断开）与
     * {@link GuardrailException}（网关主动截断）不是供应商故障：不计熔断、不重试、原样上抛。
     *
     * <p><strong>可重放契约</strong>：首帧前失败会在同目标或降级目标上重新调用本方法传入的
     * {@code invoker}；实现必须保证每次调用可安全重放——按次尝试的聚合/写出状态应在
     * {@code invoker} 内部创建或在入口处重置，否则重试会重复累计。
     *
     * @param decision      路由决策（首选 + 降级链）
     * @param invoker       针对单个目标的流式调用逻辑
     * @param streamStarted 查询「首帧是否已写给客户端」
     * @return 第一个成功目标返回的用量（可为 null）
     * @throws NoProviderAvailableException 首帧前所有目标都失败或被熔断
     */
    public Usage executeStream(RouteDecision decision, StreamInvoker invoker, BooleanSupplier streamStarted) {
        Exception lastError = null;
        for (ProviderTarget target : decision.chain()) {
            CircuitBreaker breaker = breakerRegistry.get(target.provider());
            if (!breaker.allowRequest()) {
                log.warn("目标 {} 的熔断器已打开，跳过", target);
                incCircuitOpen(target);
                continue;
            }
            // 单目标内的重试（首次 + maxRetries 次重试）
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    if (attempt > 0) {
                        incRetry(target);
                    }
                    long startNanos = System.nanoTime();
                    Usage usage = invoker.invokeStream(target);
                    recordUpstreamLatency(target, startNanos);
                    breaker.onSuccess();
                    if (attempt > 0) {
                        log.info("目标 {} 第 {} 次重试成功", target, attempt);
                    }
                    return usage;
                } catch (ClientDisconnectedException | GuardrailException e) {
                    throw e; // 非供应商故障：不计熔断、不重试
                } catch (Exception e) {
                    lastError = e;
                    boolean nonRetryable = isNonRetryable(e);
                    if (!nonRetryable) {
                        breaker.onFailure();
                    }
                    if (streamStarted.getAsBoolean()) {
                        // 首帧已写给客户端：无法换目标重放，只能断流
                        log.warn("目标 {} 在首帧写出后流式调用失败，无法降级，直接断流：{}", target, e.getMessage());
                        throw e instanceof ProviderException pe
                                ? pe
                                : new ProviderException("目标 " + target + " 流式输出已开始后上游失败：" + e.getMessage(), e);
                    }
                    // 确定性错误：不重试，直接换下一个目标
                    if (nonRetryable) {
                        log.warn("目标 {} 返回不可重试错误，换下一个目标：{}", target, e.getMessage());
                        break;
                    }
                    log.warn("目标 {} 流式调用失败（第 {} 次尝试）：{}", target, attempt + 1, e.getMessage());
                    if (attempt < maxRetries && !backoff(attempt)) {
                        throw new NoProviderAvailableException("重试等待被中断", e);
                    }
                }
            }
            // 单目标重试耗尽或确定性失败：换路由链上的下一个目标
            incFallback(target);
        }
        throw new NoProviderAvailableException("路由链上所有目标均不可用：" + decision.chain(), lastError);
    }

    /** 是否为确定性（不可重试）错误：由供应商适配器按上游状态码/配置问题标记。 */
    private static boolean isNonRetryable(Exception e) {
        return e instanceof ProviderException pe && !pe.retryable();
    }

    /** provider 维度指标（未装配 MetricsRecorder 时为 no-op，单测场景）。 */
    private void incRetry(ProviderTarget target) {
        if (metrics != null) {
            metrics.incProviderRetry(target.provider());
        }
    }

    private void incFallback(ProviderTarget target) {
        if (metrics != null) {
            metrics.incProviderFallback(target.provider());
        }
    }

    private void incCircuitOpen(ProviderTarget target) {
        if (metrics != null) {
            metrics.incCircuitOpen(target.provider());
        }
    }

    private void recordUpstreamLatency(ProviderTarget target, long startNanos) {
        if (metrics != null) {
            metrics.recordUpstreamLatency(target.provider(), (System.nanoTime() - startNanos) / 1_000_000);
        }
    }

    /**
     * 指数退避:第 n 次重试前等待 min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2^n) 毫秒,
     * 避免紧循环重试压垮已故障的供应商。
     *
     * @param attempt 刚失败的尝试序号(从 0 开始)
     * @return true 表示等待完成;false 表示线程被中断(已恢复中断标志)
     */
    private boolean backoff(int attempt) {
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L << attempt));
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
