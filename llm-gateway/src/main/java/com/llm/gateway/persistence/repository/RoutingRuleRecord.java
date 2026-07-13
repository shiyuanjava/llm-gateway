package com.llm.gateway.persistence.repository;

import java.util.List;

import com.llm.gateway.provider.ProviderTarget;

/**
 * 路由规则领域记录。
 *
 * @param alias           别名
 * @param primary         首选目标
 * @param fallbacks       降级链
 * @param maxPromptTokens 升级阈值（可空）
 * @param escalateTo      升级目标（可空）
 */
public record RoutingRuleRecord(String alias, ProviderTarget primary, List<ProviderTarget> fallbacks,
                                Integer maxPromptTokens, ProviderTarget escalateTo) {
}
