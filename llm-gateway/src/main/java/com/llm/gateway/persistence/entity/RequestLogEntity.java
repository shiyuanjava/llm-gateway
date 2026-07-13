package com.llm.gateway.persistence.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code request_log} 表实体：每次请求的审计与用量/成本记录。
 *
 * <p>它既是审计日志，也是配额计算的数据源（按租户聚合 {@code total_tokens}）。
 */
@TableName("request_log")
public class RequestLogEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求 ID（每次调用唯一，便于排障）。 */
    private String requestId;

    /** 租户标识。 */
    private String tenant;

    /** 请求的模型/别名。 */
    private String requestedModel;

    /** 实际产出响应的物理模型（失败时为空）。 */
    private String servedModel;

    /** 输入 Token 数。 */
    private Integer promptTokens;

    /** 输出 Token 数。 */
    private Integer completionTokens;

    /** 缓存读 Token 数（prompt_tokens 的内部拆分）。 */
    private Integer cacheReadTokens;

    /** 缓存写 Token 数（prompt_tokens 的内部拆分）。 */
    private Integer cacheCreationTokens;

    /** 总 Token 数（配额按租户聚合此列）。 */
    private Integer totalTokens;

    /** 本次调用成本（美元）。 */
    private BigDecimal costUsd;

    /** 是否命中缓存。 */
    private Boolean cacheHit;

    /** 状态：success / cache_hit / error。 */
    private String status;

    /** 错误码（失败时）。 */
    private String errorCode;

    /** 端到端耗时（毫秒）。 */
    private Long latencyMs;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getRequestedModel() {
        return requestedModel;
    }

    public void setRequestedModel(String requestedModel) {
        this.requestedModel = requestedModel;
    }

    public String getServedModel() {
        return servedModel;
    }

    public void setServedModel(String servedModel) {
        this.servedModel = servedModel;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getCacheReadTokens() {
        return cacheReadTokens;
    }

    public void setCacheReadTokens(Integer cacheReadTokens) {
        this.cacheReadTokens = cacheReadTokens;
    }

    public Integer getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    public void setCacheCreationTokens(Integer cacheCreationTokens) {
        this.cacheCreationTokens = cacheCreationTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(BigDecimal costUsd) {
        this.costUsd = costUsd;
    }

    public Boolean getCacheHit() {
        return cacheHit;
    }

    public void setCacheHit(Boolean cacheHit) {
        this.cacheHit = cacheHit;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
