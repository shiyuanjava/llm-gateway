package com.llm.gateway.admin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

    /**
     * 新增或修改一条规则（连同降级链整体替换）。
     *
     * @param view 规则视图
     * @return 保存后的规则视图
     */
    @Transactional
    public RoutingRuleView save(RoutingRuleView view) {
        RoutingRuleEntity entity = new RoutingRuleEntity();
        entity.setId(view.getId());
        entity.setAlias(view.getAlias());
        entity.setPrimaryProvider(view.getPrimaryProvider());
        entity.setPrimaryModel(view.getPrimaryModel());
        entity.setMaxPromptTokens(view.getMaxPromptTokens());
        entity.setEscalateProvider(view.getEscalateProvider());
        entity.setEscalateModel(view.getEscalateModel());
        if (entity.getId() == null) {
            ruleMapper.insert(entity);
        } else {
            ruleMapper.updateById(entity);
        }

        // 降级链整体替换：先删后插
        fallbackMapper.delete(
                Wrappers.<RoutingFallbackEntity>lambdaQuery().eq(RoutingFallbackEntity::getRuleAlias, view.getAlias()));
        int seq = 1;
        for (RoutingRuleView.Fallback fb : view.getFallbacks()) {
            RoutingFallbackEntity fe = new RoutingFallbackEntity();
            fe.setRuleAlias(view.getAlias());
            fe.setSeq(fb.getSeq() == null ? seq : fb.getSeq());
            fe.setProvider(fb.getProvider());
            fe.setModel(fb.getModel());
            fallbackMapper.insert(fe);
            seq++;
        }
        view.setId(entity.getId());
        return view;
    }

    /**
     * 删除一条规则及其降级链。
     *
     * @param id 规则主键
     */
    @Transactional
    public void delete(Long id) {
        RoutingRuleEntity rule = ruleMapper.selectById(id);
        if (rule == null) {
            return;
        }
        ruleMapper.deleteById(id);
        fallbackMapper.delete(
                Wrappers.<RoutingFallbackEntity>lambdaQuery().eq(RoutingFallbackEntity::getRuleAlias, rule.getAlias()));
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
