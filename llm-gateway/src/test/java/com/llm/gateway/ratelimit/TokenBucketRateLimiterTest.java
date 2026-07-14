package com.llm.gateway.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.exception.RateLimitExceededException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);

    /** 每分钟 2 次、时钟可控的限流器。 */
    private RateLimiter limiter() {
        return new TokenBucketRateLimiter(Fixtures.properties(2, 300, 1_000_000L, 5, 30, 2)) {
            @Override
            protected long clock() {
                return now.get();
            }
        };
    }

    @Test
    void shouldAllowUpToCapacityThenReject() {
        RateLimiter limiter = limiter();

        assertDoesNotThrow(() -> limiter.acquire("tenant-x"));
        assertDoesNotThrow(() -> limiter.acquire("tenant-x"));
        assertThrows(RateLimitExceededException.class, () -> limiter.acquire("tenant-x"));
    }

    @Test
    void shouldRefillOverTime() {
        RateLimiter limiter = limiter();
        limiter.acquire("tenant-x");
        limiter.acquire("tenant-x");

        // 推进 1 分钟，令牌补满
        now.set(60_000);
        assertDoesNotThrow(() -> limiter.acquire("tenant-x"));
    }

    @Test
    void shouldIsolateTenants() {
        RateLimiter limiter = limiter();
        limiter.acquire("a");
        limiter.acquire("a");

        // 另一个租户有独立的桶
        assertDoesNotThrow(() -> limiter.acquire("b"));
    }
}
