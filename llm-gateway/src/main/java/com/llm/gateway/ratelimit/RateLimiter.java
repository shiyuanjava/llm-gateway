package com.llm.gateway.ratelimit;

/**
 * 限流器抽象。把策略与实现解耦，便于从单机令牌桶演进到分布式（如 Redis）限流。
 */
public interface RateLimiter {

    /**
     * 为指定租户尝试获取一个许可；超限时抛出
     * {@link com.llm.gateway.exception.RateLimitExceededException}。
     *
     * @param tenant 租户标识
     */
    void acquire(String tenant);
}
