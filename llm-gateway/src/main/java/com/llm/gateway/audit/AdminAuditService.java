package com.llm.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

/**
 * 管理面审计服务：登录事件与写操作入库。写入失败仅告警，不影响业务响应。
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);
    private static final int MAX_DETAIL_LENGTH = 2000;

    private final AdminAuditLogMapper mapper;

    public AdminAuditService(AdminAuditLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 记录一条审计。
     *
     * @param username 操作者（登录失败时为尝试的用户名）
     * @param action   动作：LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD
     * @param resource 资源，如 api-keys/3
     * @param detail   请求体摘要（调用方负责脱敏），超长截断
     * @param clientIp 来源 IP
     * @param status   HTTP 响应码
     */
    public void record(String username, String action, String resource, String detail, String clientIp, int status) {
        try {
            AdminAuditLogEntity entity = new AdminAuditLogEntity();
            entity.setUsername(username);
            entity.setAction(action);
            entity.setResource(resource);
            entity.setDetail(
                    detail == null || detail.length() <= MAX_DETAIL_LENGTH
                            ? detail
                            : detail.substring(0, MAX_DETAIL_LENGTH));
            entity.setClientIp(clientIp);
            entity.setStatus(status);
            mapper.insert(entity);
        } catch (RuntimeException e) {
            // 审计失败不应影响业务（与 GatewayService.persistError 同思路）
            log.warn("写入管理审计日志失败：{}", e.getMessage());
        }
    }
}
