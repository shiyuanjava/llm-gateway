package com.llm.gateway.ratelimit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.QuotaExceededException;
import com.llm.gateway.persistence.repository.RequestLogRepository;

/**
 * 租户级 Token 配额服务：以 {@code request_log} 表为真值源，内存缓存 60s 内的用量，
 * 请求成功后本地累加——避免每个请求都对日志表做聚合（聚合查询依赖
 * {@code idx_request_log_tenant_tokens} 覆盖索引，见 V4 迁移）。
 *
 * <p>缓存换用 Caffeine：TTL 过期 + 最大租户数上限，伪造租户探测不会撑爆内存；
 * {@link #recordUsage} 对缓存缺失的租户先按 DB 重建快照再累加，增量不再丢失
 * （快照可能已含刚落库的这笔，向多计一侧偏差——配额宁严勿松）。
 *
 * <p>已知边界：check 与 record 非原子，并发下单个大请求可小幅越界；这是软限制，
 * 精确扣减需预扣-结算模型（多实例部署时配 Redis 计数器），当前规模不引入。
 */
@Service
public class QuotaService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final long MAX_TENANTS = 100_000;

    private final long limitPerTenant;
    private final LoadingCache<String, AtomicLong> usageCache;

    /**
     * @param requestLogRepository 请求日志仓储（聚合用量）
     * @param properties           网关配置，提供每租户 Token 上限
     */
    public QuotaService(RequestLogRepository requestLogRepository, GatewayProperties properties) {
        this.limitPerTenant = properties.quota().tokensPerTenant();
        this.usageCache = Caffeine.newBuilder()
                .maximumSize(MAX_TENANTS)
                .expireAfterWrite(CACHE_TTL)
                .build(tenant -> new AtomicLong(requestLogRepository.sumTokensByTenant(tenant)));
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
        usageCache.get(tenant).addAndGet(tokens);
    }

    /**
     * 查询某租户已消耗的 Token 数（TTL 内走缓存）。
     *
     * @param tenant 租户标识
     * @return 已消耗 Token 数
     */
    public long consumedTokens(String tenant) {
        return usageCache.get(tenant).get();
    }
}
