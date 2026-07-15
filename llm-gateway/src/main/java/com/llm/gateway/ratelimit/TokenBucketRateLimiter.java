package com.llm.gateway.ratelimit;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.RateLimitExceededException;

/**
 * 基于令牌桶的单机限流器：每租户一个桶，按「每分钟请求数」匀速补充令牌。
 *
 * <p>令牌桶相比固定窗口的好处是允许一定突发又能平滑长期速率。桶集合用 Caffeine 管理
 * （容量上限 + 访问过期），闲置租户/伪造租户的桶会被自动淘汰，不会无界增长；
 * 被淘汰的桶下次访问按满桶重建，语义等价于「久未请求即满额」。多实例部署时应替换为
 * 基于 Redis 的分布式实现——接口 {@link RateLimiter} 已为此预留。
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class TokenBucketRateLimiter implements RateLimiter {

    private static final long MAX_BUCKETS = 100_000;
    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS)
            .expireAfterAccess(IDLE_EXPIRY)
            .build();
    private final double capacity;
    private final double refillPerMillis;

    /**
     * @param properties 网关配置，提供每分钟请求数上限
     */
    public TokenBucketRateLimiter(GatewayProperties properties) {
        int rpm = properties.rateLimit().requestsPerMinute();
        this.capacity = rpm;
        this.refillPerMillis = rpm / 60_000.0;
    }

    @Override
    public void acquire(String tenant) {
        Bucket bucket = buckets.get(tenant, t -> new Bucket(capacity, clock()));
        if (!bucket.tryConsume(refillPerMillis, capacity, clock())) {
            throw new RateLimitExceededException("租户 [" + tenant + "] 请求过于频繁，请稍后重试");
        }
    }

    /**
     * 当前时间（毫秒）。抽成方法便于单元测试覆写时间。
     *
     * @return 当前毫秒时间戳
     */
    protected long clock() {
        return System.currentTimeMillis();
    }

    /**
     * 单个租户的令牌桶，自身保证线程安全。
     */
    private static final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        Bucket(double initialTokens, long now) {
            this.tokens = initialTokens;
            this.lastRefillMillis = now;
        }

        /**
         * 先按时间补充令牌，再尝试消费一个。
         *
         * @param refillPerMillis 每毫秒补充的令牌数
         * @param capacity        桶容量上限
         * @param now             当前时间
         * @return 成功消费返回 true，令牌不足返回 false
         */
        synchronized boolean tryConsume(double refillPerMillis, double capacity, long now) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerMillis);
                lastRefillMillis = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
