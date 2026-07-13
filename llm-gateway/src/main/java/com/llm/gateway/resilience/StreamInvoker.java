package com.llm.gateway.resilience;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.provider.ProviderTarget;

/**
 * 对单个目标发起一次<strong>流式</strong>调用的回调，与 {@link ProviderInvoker} 平行。
 * 实现内部逐帧回调写出，阻塞至流结束，返回上游用量（可为 null）。
 *
 * <p><strong>可重放契约</strong>：首帧前失败会在同目标或降级目标上重新调用本方法；实现必须保证
 * 每次调用可安全重放——按次尝试的聚合/写出状态应在本方法内部创建或在入口处重置，否则重试会重复累计。
 */
@FunctionalInterface
public interface StreamInvoker {

    /**
     * 流式调用指定目标。
     *
     * @param target 路由目标
     * @return 上游用量，未提供为 null
     * @throws Exception 调用失败（首帧前将触发重试/降级）
     */
    Usage invokeStream(ProviderTarget target) throws Exception;
}
