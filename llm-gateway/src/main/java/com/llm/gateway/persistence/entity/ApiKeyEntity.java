package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * {@code api_key} 表实体：API Key（SHA-256 哈希存储）与租户、角色、可用模型的映射。
 *
 * <p>库中不落明文：{@code keyHash} 存 SHA-256 哈希，{@code keyPrefix} 存展示用前缀，
 * 明文仅在创建响应中出现一次。{@code roles} 与 {@code allowedModels} 以逗号分隔的字符串存储，
 * 由仓储层解析为列表。
 */
@TableName("api_key")
public class ApiKeyEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** API Key 的 SHA-256 哈希（hex，64 位），明文不落库；不随 JSON 下发（前端只需前缀）。 */
    @JsonIgnore
    private String keyHash;

    /** 展示用前缀，如 sk-gw-a1b2c3。 */
    private String keyPrefix;

    /** 租户标识，用于限流/配额/成本归因。 */
    private String tenant;

    /** 角色列表，逗号分隔（RBAC）。 */
    private String roles;

    /** 可访问的模型/别名，逗号分隔，{@code *} 表示全部。 */
    private String allowedModels;

    /** 是否启用：true 启用，false 停用。 */
    private Boolean enabled;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public void setKeyHash(String keyHash) {
        this.keyHash = keyHash;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public String getAllowedModels() {
        return allowedModels;
    }

    public void setAllowedModels(String allowedModels) {
        this.allowedModels = allowedModels;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
