package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** The atomically maintained cumulative token usage for one tenant. */
@TableName("tenant_quota_usage")
public class TenantQuotaUsageEntity {

    @TableId(type = IdType.INPUT)
    private String tenant;

    private Long settledTokens;

    private LocalDateTime updatedAt;

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public Long getSettledTokens() {
        return settledTokens;
    }

    public void setSettledTokens(Long settledTokens) {
        this.settledTokens = settledTokens;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
