package com.llm.gateway.persistence.repository;

import java.util.List;

/**
 * 路由规则仓储抽象。
 */
public interface RoutingRuleRepository {

    /** @return 所有路由规则（含降级链） */
    List<RoutingRuleRecord> findAll();
}
