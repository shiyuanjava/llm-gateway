package com.llm.gateway.resilience;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.provider.ProviderTarget;

/**
 * 对单个目标发起一次调用的回调。
 *
 * <p>{@link ResilientExecutor} 负责「在哪些目标上、按什么顺序、重试几次」，而「具体怎么调用某个目标」
 * 由调用方通过本接口提供，从而把容错策略与供应商调用解耦。
 */
@FunctionalInterface
public interface ProviderInvoker {

    /**
     * 调用指定目标。
     *
     * @param target 路由目标
     * @return 响应
     * @throws Exception 调用失败（将触发重试/降级）
     */
    ChatCompletionResponse invoke(ProviderTarget target) throws Exception;
}
