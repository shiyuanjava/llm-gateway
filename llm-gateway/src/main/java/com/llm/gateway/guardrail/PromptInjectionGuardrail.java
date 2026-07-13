package com.llm.gateway.guardrail;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 提示词注入 / 越狱检测护栏：用一组正则匹配常见的注入话术（仅用于入站）。
 *
 * <p>这是一个轻量的启发式实现；生产环境可替换为 Llama Guard / Prompt Guard 等小模型分类器，
 * 接口不变。
 */
@Component
public class PromptInjectionGuardrail implements Guardrail {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above)\\s+instructions"),
            Pattern.compile("(?i)disregard\\s+(all\\s+)?(previous|prior)\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|in)\\b"),
            Pattern.compile("忽略(以上|之前|前面)(所有)?(的)?(指令|提示|要求)"),
            Pattern.compile("(?i)developer\\s+mode")
    );

    @Override
    public String name() {
        return "prompt-injection";
    }

    @Override
    public GuardrailResult check(String text) {
        if (text == null || text.isEmpty()) {
            return GuardrailResult.allow();
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(text).find()) {
                return GuardrailResult.block("疑似提示词注入：" + pattern.pattern());
            }
        }
        return GuardrailResult.allow();
    }
}
