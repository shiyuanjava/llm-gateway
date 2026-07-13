package com.llm.gateway.router;

import com.llm.gateway.api.dto.ChatCompletionRequest;

/**
 * 模型路由器抽象：给每个请求选出首选目标与降级链。
 *
 * <p>文章强调：路由应「从简单规则起步」，再逐步演进成可训练、可评估的系统。本接口刻意只暴露
 * 一个方法，方便日后从规则路由替换为基于成本/延迟/质量的智能路由。
 */
public interface ModelRouter {

    /**
     * 为请求做路由决策。
     *
     * @param request 请求（其 model 可能是别名或物理模型）
     * @return 路由决策
     */
    RouteDecision route(ChatCompletionRequest request);
}
