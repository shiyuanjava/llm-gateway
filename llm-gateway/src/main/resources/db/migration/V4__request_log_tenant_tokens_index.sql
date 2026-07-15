-- 配额聚合覆盖索引:QuotaService 每 60s 按租户执行 SUM(total_tokens),
-- 无索引时随 request_log 增长退化为全表扫描;(tenant, total_tokens) 覆盖索引使其只扫索引。
CREATE INDEX idx_request_log_tenant_tokens ON request_log (tenant, total_tokens);
