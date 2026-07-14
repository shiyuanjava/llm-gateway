package com.llm.gateway.persistence.repository;

/**
 * 模型计费单价领域记录。
 *
 * <p>{@code model} 支持尾部 {@code *} 通配（如 {@code mock*}）；解析时精确命中优先、
 * 其次最长前缀通配。缓存单价为 null 表示未配置，缓存 Token 退化按 input 单价计。
 *
 * @param model           模型名（可尾部 * 通配）
 * @param inputPer1k      输入每 1K Token 单价
 * @param outputPer1k     输出每 1K Token 单价
 * @param cacheReadPer1k  缓存读每 1K Token 单价（可空）
 * @param cacheWritePer1k 缓存写每 1K Token 单价（可空）
 */
public record PricingRecord(
        String model, double inputPer1k, double outputPer1k, Double cacheReadPer1k, Double cacheWritePer1k) {}
