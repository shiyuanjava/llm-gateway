package com.llm.gateway.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.llm.gateway.config.GatewayProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 注册「OpenAI 兼容」供应商 Bean。
 *
 * <p>OpenAI 与 DeepSeek 共用 {@link OpenAiCompatibleProvider}，仅 base-url 与密钥不同。
 * 要再接入一个 OpenAI 兼容供应商，只需在 {@code application.yaml} 的 {@code gateway.providers}
 * 下加一段、再在这里加一个 @Bean。
 */
@Configuration
public class ProvidersConfig {

    /**
     * OpenAI 供应商。
     *
     * @param properties   网关配置
     * @param objectMapper Jackson 3 ObjectMapper
     * @return 供应商 Bean
     */
    @Bean
    public LlmProvider openAiProvider(GatewayProperties properties, ObjectMapper objectMapper) {
        return build(properties, objectMapper, "openai", "https://api.openai.com/v1");
    }

    /**
     * DeepSeek 供应商（OpenAI 兼容）。
     *
     * @param properties   网关配置
     * @param objectMapper Jackson 3 ObjectMapper
     * @return 供应商 Bean
     */
    @Bean
    public LlmProvider deepSeekProvider(GatewayProperties properties, ObjectMapper objectMapper) {
        return build(properties, objectMapper, "deepseek", "https://api.deepseek.com");
    }

    /**
     * 按名称从配置取 base-url / api-key 构造一个 OpenAI 兼容供应商。
     *
     * @param properties     网关配置
     * @param objectMapper   Jackson 3 ObjectMapper
     * @param name           供应商名
     * @param defaultBaseUrl 缺省 base-url
     * @return 供应商
     */
    private LlmProvider build(GatewayProperties properties, ObjectMapper objectMapper,
                             String name, String defaultBaseUrl) {
        GatewayProperties.ProviderConfig config =
                properties.providers() == null ? null : properties.providers().get(name);
        String baseUrl = config == null || config.baseUrl() == null || config.baseUrl().isBlank()
                ? defaultBaseUrl : config.baseUrl();
        String apiKey = config == null ? null : config.apiKey();
        return new OpenAiCompatibleProvider(name, baseUrl, apiKey, objectMapper, properties.http());
    }
}

