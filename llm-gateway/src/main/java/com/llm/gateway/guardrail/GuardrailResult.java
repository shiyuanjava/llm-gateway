package com.llm.gateway.guardrail;

/**
 * 单条护栏的检查结果。
 *
 * @param allowed 是否放行
 * @param reason  被拦截时的原因（放行时为 null）
 */
public record GuardrailResult(boolean allowed, String reason) {

    private static final GuardrailResult ALLOWED = new GuardrailResult(true, null);

    /** @return 放行结果（单例） */
    public static GuardrailResult allow() {
        return ALLOWED;
    }

    /**
     * 构造一个拦截结果。
     *
     * @param reason 拦截原因
     * @return 拦截结果
     */
    public static GuardrailResult block(String reason) {
        return new GuardrailResult(false, reason);
    }
}
