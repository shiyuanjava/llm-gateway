package com.llm.gateway.guardrail;

import java.util.List;

import org.springframework.stereotype.Component;

import com.llm.gateway.config.GatewayProperties;

/**
 * 敏感词护栏：命中配置词表中的任意一个即拦截。可同时用于入站与出站。
 */
@Component
public class SensitiveWordGuardrail implements Guardrail {

    private final List<String> sensitiveWords;

    /**
     * @param properties 网关配置，提供敏感词词表
     */
    public SensitiveWordGuardrail(GatewayProperties properties) {
        List<String> words = properties.guardrail() == null ? null : properties.guardrail().sensitiveWords();
        this.sensitiveWords = words == null ? List.of() : List.copyOf(words);
    }

    @Override
    public String name() {
        return "sensitive-word";
    }

    @Override
    public GuardrailResult check(String text) {
        if (text == null || text.isEmpty()) {
            return GuardrailResult.allow();
        }
        for (String word : sensitiveWords) {
            if (text.contains(word)) {
                return GuardrailResult.block("命中敏感词：" + word);
            }
        }
        return GuardrailResult.allow();
    }
}
