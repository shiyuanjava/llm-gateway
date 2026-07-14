package com.llm.gateway.persistence.repository.impl;

import java.util.List;

import org.springframework.stereotype.Repository;

import com.llm.gateway.persistence.mapper.ModelPricingMapper;
import com.llm.gateway.persistence.repository.PricingRecord;
import com.llm.gateway.persistence.repository.PricingRepository;

/**
 * 基于 MyBatis-Plus 的计费仓储实现。
 */
@Repository
public class PricingRepositoryImpl implements PricingRepository {

    private final ModelPricingMapper mapper;

    public PricingRepositoryImpl(ModelPricingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<PricingRecord> findAll() {
        return mapper.selectList(null).stream()
                .map(e -> new PricingRecord(
                        e.getModel(),
                        e.getInputPer1k() == null ? 0.0 : e.getInputPer1k(),
                        e.getOutputPer1k() == null ? 0.0 : e.getOutputPer1k(),
                        e.getCacheReadPer1k(),
                        e.getCacheWritePer1k()))
                .toList();
    }
}
