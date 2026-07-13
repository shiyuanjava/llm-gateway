package com.llm.gateway.persistence.repository.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.llm.gateway.persistence.entity.RequestLogEntity;
import com.llm.gateway.persistence.mapper.RequestLogMapper;
import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;

/**
 * 基于 MyBatis-Plus 的请求日志仓储实现。
 */
@Repository
public class RequestLogRepositoryImpl implements RequestLogRepository {

    private final RequestLogMapper mapper;

    public RequestLogRepositoryImpl(RequestLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(RequestLogRecord record) {
        RequestLogEntity entity = new RequestLogEntity();
        entity.setRequestId(record.requestId());
        entity.setTenant(record.tenant());
        entity.setRequestedModel(record.requestedModel());
        entity.setServedModel(record.servedModel());
        entity.setPromptTokens(record.promptTokens());
        entity.setCompletionTokens(record.completionTokens());
        entity.setCacheReadTokens(record.cacheReadTokens());
        entity.setCacheCreationTokens(record.cacheCreationTokens());
        entity.setTotalTokens(record.totalTokens());
        entity.setCostUsd(BigDecimal.valueOf(record.costUsd()));
        entity.setCacheHit(record.cacheHit());
        entity.setStatus(record.status());
        entity.setErrorCode(record.errorCode());
        entity.setLatencyMs(record.latencyMs());
        mapper.insert(entity);
    }

    @Override
    public long sumTokensByTenant(String tenant) {
        QueryWrapper<RequestLogEntity> query = new QueryWrapper<>();
        query.select("IFNULL(SUM(total_tokens), 0) AS total").eq("tenant", tenant);
        List<Map<String, Object>> rows = mapper.selectMaps(query);
        if (rows.isEmpty() || rows.get(0).get("total") == null) {
            return 0L;
        }
        return ((Number) rows.get(0).get("total")).longValue();
    }
}
