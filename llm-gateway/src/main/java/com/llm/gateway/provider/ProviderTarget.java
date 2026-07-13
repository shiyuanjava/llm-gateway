package com.llm.gateway.provider;

/**
 * 路由目标：指明把请求送到哪个供应商的哪个物理模型。
 *
 * @param provider 供应商名（需与配置中 {@code gateway.providers} 的 key 一致）
 * @param model    物理模型名
 */
public record ProviderTarget(String provider, String model) {

    @Override
    public String toString() {
        return provider + ":" + model;
    }
}
