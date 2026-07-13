package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code routing_rule} 表实体：路由规则（别名 -> 首选 + 可选升级目标）。降级链见
 * {@link RoutingFallbackEntity}。
 */
@TableName("routing_rule")
public class RoutingRuleEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 逻辑模型别名（如 auto/cheap/smart），业务请求填这个。 */
    private String alias;

    /** 首选供应商名（对应 gateway.providers 的 key）。 */
    private String primaryProvider;

    /** 首选物理模型名。 */
    private String primaryModel;

    /** 触发升级到大模型的提示词 Token 阈值，可空。 */
    private Integer maxPromptTokens;

    /** 超过阈值时改用的供应商，可空。 */
    private String escalateProvider;

    /** 超过阈值时改用的物理模型，可空。 */
    private String escalateModel;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getPrimaryProvider() {
        return primaryProvider;
    }

    public void setPrimaryProvider(String primaryProvider) {
        this.primaryProvider = primaryProvider;
    }

    public String getPrimaryModel() {
        return primaryModel;
    }

    public void setPrimaryModel(String primaryModel) {
        this.primaryModel = primaryModel;
    }

    public Integer getMaxPromptTokens() {
        return maxPromptTokens;
    }

    public void setMaxPromptTokens(Integer maxPromptTokens) {
        this.maxPromptTokens = maxPromptTokens;
    }

    public String getEscalateProvider() {
        return escalateProvider;
    }

    public void setEscalateProvider(String escalateProvider) {
        this.escalateProvider = escalateProvider;
    }

    public String getEscalateModel() {
        return escalateModel;
    }

    public void setEscalateModel(String escalateModel) {
        this.escalateModel = escalateModel;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
