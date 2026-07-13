package com.llm.gateway.persistence.repository.impl;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.persistence.entity.RoutingFallbackEntity;
import com.llm.gateway.persistence.entity.RoutingRuleEntity;
import com.llm.gateway.persistence.mapper.RoutingFallbackMapper;
import com.llm.gateway.persistence.mapper.RoutingRuleMapper;
import com.llm.gateway.persistence.repository.RoutingRuleRecord;
import com.llm.gateway.persistence.repository.RoutingRuleRepository;
import com.llm.gateway.provider.ProviderTarget;

/**
 * 基于 MyBatis-Plus 的路由规则仓储实现：组合 {@code routing_rule} 与 {@code routing_fallback} 两表。
 */
@Repository
public class RoutingRuleRepositoryImpl implements RoutingRuleRepository {

    private final RoutingRuleMapper ruleMapper;
    private final RoutingFallbackMapper fallbackMapper;

    public RoutingRuleRepositoryImpl(RoutingRuleMapper ruleMapper, RoutingFallbackMapper fallbackMapper) {
        this.ruleMapper = ruleMapper;
        this.fallbackMapper = fallbackMapper;
    }

    @Override
    public List<RoutingRuleRecord> findAll() {
        // 一次查全所有降级链,内存按别名分组,避免每条规则一次查询(N+1)
        Map<String, List<ProviderTarget>> fallbacksByAlias = fallbackMapper.selectList(
                        Wrappers.<RoutingFallbackEntity>lambdaQuery().orderByAsc(RoutingFallbackEntity::getSeq))
                .stream()
                .collect(Collectors.groupingBy(RoutingFallbackEntity::getRuleAlias,
                        Collectors.mapping(f -> new ProviderTarget(f.getProvider(), f.getModel()),
                                Collectors.toList())));
        return ruleMapper.selectList(null).stream()
                .map(rule -> toRecord(rule, fallbacksByAlias.getOrDefault(rule.getAlias(), List.of())))
                .toList();
    }

    /**
     * 把规则实体(连同其降级链)转换成领域记录。
     *
     * @param rule      规则实体
     * @param fallbacks 该规则的降级链
     * @return 路由规则记录
     */
    private RoutingRuleRecord toRecord(RoutingRuleEntity rule, List<ProviderTarget> fallbacks) {
        ProviderTarget primary = new ProviderTarget(rule.getPrimaryProvider(), rule.getPrimaryModel());
        ProviderTarget escalateTo = rule.getEscalateProvider() == null ? null
                : new ProviderTarget(rule.getEscalateProvider(), rule.getEscalateModel());
        return new RoutingRuleRecord(rule.getAlias(), primary, fallbacks, rule.getMaxPromptTokens(), escalateTo);
    }
}
