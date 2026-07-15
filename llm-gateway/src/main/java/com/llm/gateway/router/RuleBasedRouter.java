package com.llm.gateway.router;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.config.ConfigReloadable;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.persistence.repository.RoutingRuleRecord;
import com.llm.gateway.persistence.repository.RoutingRuleRepository;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.provider.TokenEstimator;

/**
 * 基于规则的模型路由器（简单起步、可演进），规则从数据库 {@code routing_rule} 表加载。
 *
 * <p>路由逻辑：
 * <ol>
 *   <li>请求 model 为 {@code "default"}：路由到环境变量 {@code LLM_PROVIDER}/{@code LLM_MODEL} 指定的默认 LLM。</li>
 *   <li>命中<strong>别名规则</strong>：用规则的首选 + 降级链；提示词 Token 超阈值且配置了升级目标时改用大模型。</li>
 *   <li>否则把 model 视为<strong>物理模型</strong>，按名称前缀推断供应商直接路由。</li>
 * </ol>
 *
 * <p>规则缓存可通过 {@link #reload()} 热刷新（管理端改动路由后调用）。
 */
@Component
public class RuleBasedRouter implements ModelRouter, ConfigReloadable {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedRouter.class);
    private static final String DEFAULT_ALIAS = "default";
    private static final ProviderTarget MOCK_FALLBACK = new ProviderTarget("mock", "mock-small");

    private final RoutingRuleRepository routingRuleRepository;
    private final String defaultModel;
    private final ProviderTarget defaultLlm;
    private volatile Map<String, RoutingRuleRecord> rules;

    /**
     * @param routingRuleRepository 路由规则仓储（数据库）
     * @param properties            网关配置，提供默认模型与默认 LLM
     */
    public RuleBasedRouter(RoutingRuleRepository routingRuleRepository, GatewayProperties properties) {
        this.routingRuleRepository = routingRuleRepository;
        this.defaultModel = properties.routing() == null
                ? "deepseek-v4-pro"
                : properties.routing().defaultModel();
        this.defaultLlm = properties.llm() == null
                ? null
                : new ProviderTarget(
                        properties.llm().provider(), properties.llm().model());
        reload();
    }

    @Override
    public void reload() {
        Map<String, RoutingRuleRecord> index = new HashMap<>();
        for (RoutingRuleRecord rule : routingRuleRepository.findAll()) {
            index.put(rule.alias(), rule);
        }
        this.rules = Map.copyOf(index);
    }

    @Override
    public RouteDecision route(ChatCompletionRequest request) {
        if (DEFAULT_ALIAS.equals(request.model()) && defaultLlm != null) {
            return new RouteDecision(defaultLlm, List.of(MOCK_FALLBACK));
        }
        RoutingRuleRecord rule = rules.get(request.model());
        if (rule != null) {
            return routeByRule(request, rule);
        }
        return routeByPhysicalModel(request.model());
    }

    /**
     * 按命中的别名规则路由，必要时升级到大模型。
     *
     * @param request 请求
     * @param rule    命中的规则
     * @return 路由决策
     */
    private RouteDecision routeByRule(ChatCompletionRequest request, RoutingRuleRecord rule) {
        boolean shouldEscalate = rule.maxPromptTokens() != null
                && rule.escalateTo() != null
                && TokenEstimator.estimate(request.messages()) > rule.maxPromptTokens();
        if (shouldEscalate) {
            log.debug("提示词超过 {} Token，别名 [{}] 升级到 {}", rule.maxPromptTokens(), rule.alias(), rule.escalateTo());
            List<ProviderTarget> escalatedFallbacks = new ArrayList<>();
            escalatedFallbacks.add(rule.primary());
            escalatedFallbacks.addAll(rule.fallbacks());
            return new RouteDecision(rule.escalateTo(), escalatedFallbacks);
        }
        return new RouteDecision(rule.primary(), rule.fallbacks());
    }

    /**
     * 把未命中别名的 model 当作物理模型，按前缀推断供应商。
     *
     * @param model 物理模型名
     * @return 路由决策
     */
    private RouteDecision routeByPhysicalModel(String model) {
        String provider = inferProvider(model);
        if (provider == null) {
            log.warn("无法识别模型 [{}]，退回默认模型 [{}]", model, defaultModel);
            return new RouteDecision(new ProviderTarget(inferProvider(defaultModel), defaultModel), List.of());
        }
        return new RouteDecision(new ProviderTarget(provider, model), List.of());
    }

    /**
     * 按模型名前缀推断供应商。
     *
     * @param model 物理模型名
     * @return 供应商名，无法识别返回 null
     */
    private String inferProvider(String model) {
        if (model == null) {
            return null;
        }
        if (model.startsWith("deepseek")) {
            return "deepseek";
        }
        if (model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("text-")) {
            return "openai";
        }
        if (model.startsWith("claude")) {
            return "anthropic";
        }
        if (model.startsWith("mock")) {
            return "mock";
        }
        return null;
    }
}
