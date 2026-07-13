package com.llm.gateway.router;

import java.util.ArrayList;
import java.util.List;

import com.llm.gateway.provider.ProviderTarget;

/**
 * 路由决策结果：一个首选目标 + 一条降级链。
 *
 * @param primary   首选目标
 * @param fallbacks 首选失败后依次尝试的降级目标
 */
public record RouteDecision(ProviderTarget primary, List<ProviderTarget> fallbacks) {

    /**
     * 返回「首选 + 降级」的完整尝试顺序。
     *
     * @return 按优先级排列的目标列表（首元素为首选）
     */
    public List<ProviderTarget> chain() {
        List<ProviderTarget> chain = new ArrayList<>();
        chain.add(primary);
        if (fallbacks != null) {
            chain.addAll(fallbacks);
        }
        return chain;
    }
}
