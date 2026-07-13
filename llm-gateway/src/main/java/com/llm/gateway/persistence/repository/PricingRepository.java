package com.llm.gateway.persistence.repository;

import java.util.List;

/**
 * 模型计费仓储抽象。
 */
public interface PricingRepository {

    /** @return 所有模型计费记录 */
    List<PricingRecord> findAll();
}
