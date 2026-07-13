package com.llm.gateway.persistence.repository;

/**
 * 请求日志领域记录（写入数据库的一行审计/用量记录）。
 *
 * @param requestId           请求 ID
 * @param tenant              租户
 * @param requestedModel      请求的模型/别名
 * @param servedModel         实际产出响应的模型（失败时可空）
 * @param promptTokens        输入 Token（含缓存）
 * @param completionTokens    输出 Token
 * @param totalTokens         总 Token
 * @param cacheReadTokens     缓存读 Token（prompt 的内部拆分；估算路径恒 0）
 * @param cacheCreationTokens 缓存写 Token（prompt 的内部拆分；估算路径恒 0）
 * @param costUsd             成本（美元）
 * @param cacheHit            是否命中缓存
 * @param status              状态：{@code success} / {@code cache_hit} / {@code error}
 *                            / {@code client_aborted} / {@code guardrail_truncated}
 * @param errorCode           错误码（失败时）
 * @param latencyMs           端到端耗时（毫秒）
 */
public record RequestLogRecord(String requestId, String tenant, String requestedModel, String servedModel,
                               int promptTokens, int completionTokens, int totalTokens,
                               int cacheReadTokens, int cacheCreationTokens, double costUsd,
                               boolean cacheHit, String status, String errorCode, long latencyMs) {
}
