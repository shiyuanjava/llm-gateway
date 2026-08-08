package com.llm.gateway.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.admin.dto.RoutingRuleWriteRequest;
import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.persistence.entity.RoutingFallbackEntity;
import com.llm.gateway.persistence.entity.RoutingRuleEntity;
import com.llm.gateway.persistence.mapper.RoutingFallbackMapper;
import com.llm.gateway.persistence.mapper.RoutingRuleMapper;

/**
 * 路由规则管理服务：组合 {@code routing_rule} 与 {@code routing_fallback} 两表的增删改查，
 * 把降级链的「整体替换」封装为一个事务。
 */
@Service
public class RoutingRuleAdminService {

    private final RoutingRuleMapper ruleMapper;
    private final RoutingFallbackMapper fallbackMapper;

    public RoutingRuleAdminService(RoutingRuleMapper ruleMapper, RoutingFallbackMapper fallbackMapper) {
        this.ruleMapper = ruleMapper;
        this.fallbackMapper = fallbackMapper;
    }

    /** @return 所有规则视图（含降级链） */
    public List<RoutingRuleView> list() {
        Map<String, List<RoutingFallbackEntity>> fallbacksByAlias = fallbackMapper
                .selectList(Wrappers.<RoutingFallbackEntity>lambdaQuery().orderByAsc(RoutingFallbackEntity::getSeq))
                .stream()
                .collect(Collectors.groupingBy(RoutingFallbackEntity::getRuleAlias));
        return ruleMapper
                .selectList(Wrappers.<RoutingRuleEntity>lambdaQuery().orderByAsc(RoutingRuleEntity::getId))
                .stream()
                .map(rule -> toView(rule, fallbacksByAlias.getOrDefault(rule.getAlias(), List.of())))
                .toList();
    }

    /** Create a rule and replace its fallback chain in one transaction. */
    @Transactional
    public RoutingRuleView create(RoutingRuleWriteRequest request) {
        RoutingRuleEntity entity = entityOf(request);
        ruleMapper.insert(entity);
        replaceFallbacks(entity.getAlias(), request.fallbacks());
        return toView(entity, fallbackEntities(entity.getAlias(), request.fallbacks()));
    }

    /** Update a rule while keeping its alias immutable. */
    @Transactional
    public RoutingRuleView update(Long id, RoutingRuleWriteRequest request) {
        RoutingRuleEntity existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw AdminApiException.notFound("路由规则不存在");
        }
        if (!existing.getAlias().equals(request.alias().trim())) {
            throw AdminApiException.conflict("路由 alias 不允许修改");
        }
        int affected = ruleMapper.update(
                null,
                Wrappers.<RoutingRuleEntity>update()
                        .eq("id", id)
                        .set("primary_provider", request.primaryProvider().trim())
                        .set("primary_model", request.primaryModel().trim())
                        .set("max_prompt_tokens", request.maxPromptTokens())
                        .set("escalate_provider", request.normalizedEscalateProvider())
                        .set("escalate_model", request.normalizedEscalateModel()));
        if (affected != 1) {
            throw AdminApiException.notFound("路由规则不存在");
        }
        replaceFallbacks(existing.getAlias(), request.fallbacks());
        existing.setPrimaryProvider(request.primaryProvider().trim());
        existing.setPrimaryModel(request.primaryModel().trim());
        existing.setMaxPromptTokens(request.maxPromptTokens());
        existing.setEscalateProvider(request.normalizedEscalateProvider());
        existing.setEscalateModel(request.normalizedEscalateModel());
        return toView(existing, fallbackEntities(existing.getAlias(), request.fallbacks()));
    }

    /** Delete a rule and its fallback chain; missing rows are explicit 404s. */
    @Transactional
    public void delete(Long id) {
        RoutingRuleEntity existing = ruleMapper.selectById(id);
        if (existing == null) {
            throw AdminApiException.notFound("路由规则不存在");
        }
        if (ruleMapper.deleteById(id) != 1) {
            throw AdminApiException.notFound("路由规则不存在");
        }
        fallbackMapper.delete(Wrappers.<RoutingFallbackEntity>lambdaQuery()
                .eq(RoutingFallbackEntity::getRuleAlias, existing.getAlias()));
    }

    private RoutingRuleEntity entityOf(RoutingRuleWriteRequest request) {
        RoutingRuleEntity entity = new RoutingRuleEntity();
        entity.setAlias(request.alias().trim());
        entity.setPrimaryProvider(request.primaryProvider().trim());
        entity.setPrimaryModel(request.primaryModel().trim());
        entity.setMaxPromptTokens(request.maxPromptTokens());
        entity.setEscalateProvider(request.normalizedEscalateProvider());
        entity.setEscalateModel(request.normalizedEscalateModel());
        return entity;
    }

    private void replaceFallbacks(String alias, List<RoutingRuleWriteRequest.Fallback> fallbacks) {
        fallbackMapper.delete(
                Wrappers.<RoutingFallbackEntity>lambdaQuery().eq(RoutingFallbackEntity::getRuleAlias, alias));
        for (RoutingFallbackEntity entity : fallbackEntities(alias, fallbacks)) {
            fallbackMapper.insert(entity);
        }
    }

    private List<RoutingFallbackEntity> fallbackEntities(
            String alias, List<RoutingRuleWriteRequest.Fallback> fallbacks) {
        List<RoutingFallbackEntity> entities = new ArrayList<>(fallbacks.size());
        for (int index = 0; index < fallbacks.size(); index++) {
            RoutingRuleWriteRequest.Fallback fallback = fallbacks.get(index);
            RoutingFallbackEntity entity = new RoutingFallbackEntity();
            entity.setRuleAlias(alias);
            entity.setSeq(index + 1);
            entity.setProvider(fallback.provider().trim());
            entity.setModel(fallback.model().trim());
            entities.add(entity);
        }
        return entities;
    }

    /**
     * 实体（含降级链）转视图。
     *
     * @param rule      规则实体
     * @param fallbacks 该规则的降级链实体
     * @return 视图
     */
    private RoutingRuleView toView(RoutingRuleEntity rule, List<RoutingFallbackEntity> fallbacks) {
        RoutingRuleView view = new RoutingRuleView();
        view.setId(rule.getId());
        view.setAlias(rule.getAlias());
        view.setPrimaryProvider(rule.getPrimaryProvider());
        view.setPrimaryModel(rule.getPrimaryModel());
        view.setMaxPromptTokens(rule.getMaxPromptTokens());
        view.setEscalateProvider(rule.getEscalateProvider());
        view.setEscalateModel(rule.getEscalateModel());
        view.setFallbacks(fallbacks.stream()
                .map(f -> {
                    RoutingRuleView.Fallback fb = new RoutingRuleView.Fallback();
                    fb.setSeq(f.getSeq());
                    fb.setProvider(f.getProvider());
                    fb.setModel(f.getModel());
                    return fb;
                })
                .toList());
        return view;
    }
}
