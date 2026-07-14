package com.llm.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.QuotaExceededException;
import com.llm.gateway.persistence.repository.RequestLogRepository;

/**
 * 租户级 Token 配额服务：以 {@code request_log} 表为真值源，内存缓存 60s 内的用量，
 * 请求成功后本地累加——避免每个请求都对日志表做全表聚合。
 */
@Service
public class QuotaService {

    private static final long CACHE_TTL_MS = 60_000;

    private final RequestLogRepository requestLogRepository;
    private final long limitPerTenant;
    private final ConcurrentHashMap<String, CachedUsage> usageCache = new ConcurrentHashMap<>();

    /**
     * 单租户用量缓存：DB 快照 + 快照后的本地增量。
     */
    private record CachedUsage(long loadedAtMs, AtomicLong tokens) {}

    /**
     * @param requestLogRepository 请求日志仓储（聚合用量）
     * @param properties           网关配置，提供每租户 Token 上限
     */
    public QuotaService(RequestLogRepository requestLogRepository, GatewayProperties properties) {
        this.requestLogRepository = requestLogRepository;
        this.limitPerTenant = properties.quota().tokensPerTenant();
    }

    /**
     * 调用前预检查：若该租户已消耗的 Token 达到上限则抛出 {@link QuotaExceededException}。
     *
     * @param tenant 租户标识
     */
    public void checkQuota(String tenant) {
        if (consumedTokens(tenant) >= limitPerTenant) {
            throw new QuotaExceededException("租户 [" + tenant + "] 的 Token 配额已用尽（上限 " + limitPerTenant + "）");
        }
    }

    /**
     * 请求成功后本地累加用量，保证 TTL 窗口内配额判断仍然准确。
     *
     * @param tenant 租户标识
     * @param tokens 本次消耗的 Token 数
     */
    public void recordUsage(String tenant, long tokens) {
        CachedUsage cached = usageCache.get(tenant);
        if (cached != null) {
            cached.tokens().addAndGet(tokens);
        }
    }

    /**
     * 查询某租户已消耗的 Token 数（TTL 内走缓存）。
     *
     * @param tenant 租户标识
     * @return 已消耗 Token 数
     */
    public long consumedTokens(String tenant) {
        long now = System.currentTimeMillis();
        CachedUsage cached = usageCache.compute(tenant, (t, old) -> {
            if (old != null && now - old.loadedAtMs() < CACHE_TTL_MS) {
                return old;
            }
            return new CachedUsage(now, new AtomicLong(requestLogRepository.sumTokensByTenant(t)));
        });
        return cached.tokens().get();
    }
}
