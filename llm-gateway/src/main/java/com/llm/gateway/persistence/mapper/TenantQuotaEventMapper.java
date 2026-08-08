package com.llm.gateway.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.gateway.persistence.entity.TenantQuotaEventEntity;

/** Mapper for idempotent per-request quota events. */
public interface TenantQuotaEventMapper extends BaseMapper<TenantQuotaEventEntity> {

    /**
     * Inserts an event only when its request id has not been seen before.
     *
     * @return one for a newly inserted event, zero for an existing request id
     */
    @Insert(
            """
            INSERT IGNORE INTO tenant_quota_event (request_id, tenant, tokens)
            VALUES (#{requestId}, #{tenant}, #{tokens})
            """)
    int insertIgnore(
            @Param("requestId") String requestId, @Param("tenant") String tenant, @Param("tokens") long tokens);

    @Select(
            """
            SELECT id, request_id, tenant, tokens, created_at
            FROM tenant_quota_event
            WHERE request_id = #{requestId}
            """)
    TenantQuotaEventEntity selectByRequestId(@Param("requestId") String requestId);
}
