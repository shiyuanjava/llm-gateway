package com.llm.gateway.guardrail;

/**
 * 安全护栏抽象：对一段文本做确定性检查并返回是否放行。
 *
 * <p>「确定性护栏」是 Harness 工程区别于纯提示词工程的关键——把规则用代码（而非模糊的自然语言）
 * 强制执行，结果可预测、可测试。多个护栏可组合成入站/出站检查链。
 */
public interface Guardrail {

    /** @return 护栏名称（用于日志与拦截原因） */
    String name();

    /**
     * 检查一段文本。
     *
     * @param text 待检查文本
     * @return 检查结果
     */
    GuardrailResult check(String text);
}
