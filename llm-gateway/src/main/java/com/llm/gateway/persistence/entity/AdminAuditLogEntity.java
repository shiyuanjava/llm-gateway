package com.llm.gateway.persistence.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * {@code admin_audit_log} 表实体：管理面审计日志（登录与写操作）。
 */
@TableName("admin_audit_log")
public class AdminAuditLogEntity {

    /** 主键，自增。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户名（登录失败时为尝试的用户名）。 */
    private String username;

    /** 动作：LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD。 */
    private String action;

    /** 操作资源，如 api-keys/3、auth/login。 */
    private String resource;

    /** 请求体摘要（脱敏后）。 */
    private String detail;

    /** 来源 IP。 */
    private String clientIp;

    /** HTTP 响应码。 */
    private Integer status;

    /** 发生时间。 */
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
