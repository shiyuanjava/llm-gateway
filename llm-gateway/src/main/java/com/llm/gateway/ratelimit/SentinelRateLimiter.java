package com.llm.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.llm.gateway.exception.RateLimitExceededException;

/**
 * 基于 Sentinel 热点参数规则的限流器：资源固定为 {@link #RESOURCE}，租户作为参数索引 0。
 *
 * <p>阈值不在代码里：规则由 Nacos 数据源（dataId {@code llm-gateway-param-flow-rules}）
 * 动态下发，改规则即时生效、重启不丢。超限统一转 {@link RateLimitExceededException}，
 * 走既有 429 响应链。
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "sentinel")
public class SentinelRateLimiter implements RateLimiter {

    /** Sentinel 资源名，Nacos 里的限流规则与此对应。 */
    public static final String RESOURCE = "chat-completion";

    @Override
    public void acquire(String tenant) {
        try (Entry ignored = SphU.entry(RESOURCE, EntryType.IN, 1, tenant)) {
            // 进入即放行；try-with-resources 保证 exit,统计窗口正确
        } catch (BlockException e) {
            throw new RateLimitExceededException("租户 [" + tenant + "] 请求过于频繁，请稍后重试");
        }
    }
}
