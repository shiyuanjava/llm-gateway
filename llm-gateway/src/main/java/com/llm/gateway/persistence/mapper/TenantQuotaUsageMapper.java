package com.llm.gateway.persistence.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.gateway.persistence.entity.TenantQuotaUsageEntity;

/** Mapper for atomically maintaining tenant quota usage totals. */
public interface TenantQuotaUsageMapper extends BaseMapper<TenantQuotaUsageEntity> {

    /**
     * Adds tokens to a tenant aggregate, creating it when necessary.
     *
     * <p>The parameter is repeated in the update expression instead of using MySQL's deprecated
     * {@code VALUES(...)} function, keeping the statement compatible with current MySQL 8
     * versions while retaining support for older MySQL 8 releases.
     */
    @Insert(
            """
            INSERT INTO tenant_quota_usage (tenant, settled_tokens)
            VALUES (#{tenant}, #{tokens})
            ON DUPLICATE KEY UPDATE settled_tokens = settled_tokens + #{tokens}
            """)
    int addSettledTokens(@Param("tenant") String tenant, @Param("tokens") long tokens);

    @Select("SELECT settled_tokens FROM tenant_quota_usage WHERE tenant = #{tenant}")
    Long selectSettledTokens(@Param("tenant") String tenant);
}
