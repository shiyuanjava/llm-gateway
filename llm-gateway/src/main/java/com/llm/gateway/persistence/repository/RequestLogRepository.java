package com.llm.gateway.persistence.repository;

/**
 * 请求日志仓储抽象：写入审计记录、按租户聚合已用 Token（配额计算用）。
 */
public interface RequestLogRepository {

    /**
     * 写入一条请求日志。
     *
     * @param record 日志记录
     */
    void save(RequestLogRecord record);

    /**
     * 统计某租户累计消耗的 Token。
     *
     * @param tenant 租户
     * @return 累计 Token 数
     */
    long sumTokensByTenant(String tenant);
}
