package com.llm.gateway.ratelimit;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.llm.gateway.exception.RateLimitExceededException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SentinelRateLimiter：内存中注册热点参数规则,验证超阈值抛 {@link RateLimitExceededException}。
 */
class SentinelRateLimiterTest {

    private final SentinelRateLimiter limiter = new SentinelRateLimiter();

    @AfterEach
    void cleanRules() {
        ParamFlowRuleManager.loadRules(List.of());
    }

    @Test
    void withinThresholdPasses() {
        loadRule(1000);
        assertThatCode(() -> limiter.acquire("tenant-a")).doesNotThrowAnyException();
    }

    @Test
    void exceedingThresholdThrows429Exception() {
        loadRule(1);
        limiter.acquire("tenant-b");
        assertThatThrownBy(() -> limiter.acquire("tenant-b")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void tenantsAreIsolated() {
        loadRule(1);
        limiter.acquire("tenant-c");
        assertThatCode(() -> limiter.acquire("tenant-d")).doesNotThrowAnyException();
    }

    private void loadRule(double qps) {
        ParamFlowRule rule =
                new ParamFlowRule(SentinelRateLimiter.RESOURCE).setParamIdx(0).setCount(qps);
        ParamFlowRuleManager.loadRules(List.of(rule));
    }
}
