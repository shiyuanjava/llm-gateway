package com.llm.gateway.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 网关的外置配置（前缀 {@code gateway}）。
 *
 * <p>注意：API Key、路由规则、计费单价已迁移到<strong>数据库</strong>（见 {@code schema.sql}），
 * 不再放在这里。本配置只保留运营参数：默认模型、默认 LLM、供应商接入、限流、配额、缓存、护栏、容错。
 *
 * @param routing    路由相关参数（默认物理模型）
 * @param llm        通过 {@code LLM_PROVIDER}/{@code LLM_MODEL} 指定的默认 LLM
 * @param providers  各供应商接入信息，key 为供应商名
 * @param rateLimit  限流配置
 * @param quota      租户配额配置
 * @param cache      缓存配置
 * @param guardrail  安全护栏配置
 * @param resilience 容错配置
 * @param http       出站 HTTP 客户端配置（调用各 LLM 供应商）
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Routing routing,
        Llm llm,
        Map<String, ProviderConfig> providers,
        RateLimit rateLimit,
        Quota quota,
        Cache cache,
        Guardrail guardrail,
        Resilience resilience,
        Http http) {

    /**
     * 出站 HTTP 客户端配置（调用各 LLM 供应商）。
     *
     * @param connectTimeoutMs 连接超时毫秒
     * @param readTimeoutMs    读超时毫秒
     */
    public record Http(int connectTimeoutMs, int readTimeoutMs) {
    }

    /**
     * 路由参数。
     *
     * @param defaultModel 当请求 model 既非别名也无法识别前缀时回退的物理模型
     */
    public record Routing(String defaultModel) {
    }

    /**
     * 默认 LLM（由环境变量 {@code LLM_PROVIDER}/{@code LLM_MODEL} 注入）。当请求 model 为
     * {@code "default"} 时路由到它。
     *
     * @param provider 供应商名
     * @param model    物理模型名
     */
    public record Llm(String provider, String model) {
    }

    /**
     * 供应商接入信息。
     *
     * @param baseUrl 供应商 API 基地址
     * @param apiKey  访问密钥
     */
    public record ProviderConfig(String baseUrl, String apiKey) {
    }

    /**
     * 限流配置。
     *
     * @param requestsPerMinute 每租户每分钟允许的请求数
     */
    public record RateLimit(int requestsPerMinute) {
    }

    /**
     * 配额配置。
     *
     * @param tokensPerTenant 每租户允许消耗的累计 Token 上限
     */
    public record Quota(long tokensPerTenant) {
    }

    /**
     * 缓存配置。
     *
     * @param enabled    是否启用缓存
     * @param store      缓存后端:memory(默认)/ redis。由 @ConditionalOnProperty 消费,选择 ResponseCache 实现
     * @param ttlSeconds 缓存条目存活秒数
     * @param semantic   语义缓存子配置
     */
    public record Cache(boolean enabled, String store, long ttlSeconds, Semantic semantic) {

        /**
         * 语义缓存配置。
         *
         * @param enabled             是否启用
         * @param similarityThreshold 命中所需的余弦相似度阈值
         */
        public record Semantic(boolean enabled, double similarityThreshold) {
        }
    }

    /**
     * 安全护栏配置。
     *
     * @param sensitiveWords 敏感词词表
     */
    public record Guardrail(List<String> sensitiveWords) {
    }

    /**
     * 容错配置。
     *
     * @param maxRetries     单目标最大重试次数
     * @param circuitBreaker 熔断器参数
     */
    public record Resilience(int maxRetries, CircuitBreakerConfig circuitBreaker) {

        /**
         * 熔断器参数。
         *
         * @param failureThreshold 连续失败多少次后打开
         * @param openSeconds      打开后冷却秒数
         */
        public record CircuitBreakerConfig(int failureThreshold, int openSeconds) {
        }
    }
}
