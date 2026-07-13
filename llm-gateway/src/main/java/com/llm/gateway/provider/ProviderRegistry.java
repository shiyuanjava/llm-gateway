package com.llm.gateway.provider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.llm.gateway.exception.ProviderException;

/**
 * 供应商注册表：启动时收集所有 {@link LlmProvider} 实现，按名称建立索引供路由层按目标取用。
 */
@Component
public class ProviderRegistry {

    private final Map<String, LlmProvider> providers;

    /**
     * @param providerBeans 容器中所有的供应商实现
     */
    public ProviderRegistry(List<LlmProvider> providerBeans) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toUnmodifiableMap(LlmProvider::name, Function.identity()));
    }

    /**
     * 按名称取供应商；不存在视为可降级的故障，抛出 {@link ProviderException}。
     *
     * @param name 供应商名
     * @return 供应商实现
     */
    public LlmProvider get(String name) {
        LlmProvider provider = providers.get(name);
        if (provider == null) {
            throw new ProviderException("未知供应商：" + name);
        }
        return provider;
    }
}
