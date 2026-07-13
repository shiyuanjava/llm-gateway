package com.llm.gateway.guardrail;

import java.util.List;

import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.exception.GuardrailException;

/**
 * 护栏引擎：把多个 {@link Guardrail} 编排成入站、出站两条检查链。
 *
 * <p>入站检查请求中的用户/系统消息，出站检查模型回复。任一护栏拦截即抛出
 * {@link GuardrailException}，请求中止。
 */
@Component
public class GuardrailEngine {

    private final List<Guardrail> inputGuardrails;
    private final List<Guardrail> outputGuardrails;

    /**
     * @param sensitiveWordGuardrail   敏感词护栏（入站 + 出站）
     * @param promptInjectionGuardrail 注入检测护栏（仅入站）
     */
    public GuardrailEngine(SensitiveWordGuardrail sensitiveWordGuardrail,
                           PromptInjectionGuardrail promptInjectionGuardrail) {
        this.inputGuardrails = List.of(sensitiveWordGuardrail, promptInjectionGuardrail);
        this.outputGuardrails = List.of(sensitiveWordGuardrail);
    }

    /**
     * 入站检查：拼接请求中的所有消息文本后逐一过护栏。
     *
     * @param request 请求
     * @throws GuardrailException 命中任一入站护栏
     */
    public void checkInput(ChatCompletionRequest request) {
        String text = concatMessages(request.messages());
        runChain(inputGuardrails, text, "入站");
    }

    /**
     * 出站检查：取模型回复文本后逐一过护栏。
     *
     * @param response 响应
     * @throws GuardrailException 命中任一出站护栏
     */
    public void checkOutput(ChatCompletionResponse response) {
        checkOutputText(response.firstContent());
    }

    /**
     * 出站检查（文本版）：流式场景对「累计文本」增量调用。
     * 敏感词表小且为子串匹配，重复全量检查开销可忽略。
     *
     * @param text 已累计的输出文本
     * @throws GuardrailException 命中任一出站护栏
     */
    public void checkOutputText(String text) {
        runChain(outputGuardrails, text, "出站");
    }

    /**
     * 依次执行护栏链，遇到拦截即抛异常。
     *
     * @param guardrails 护栏链
     * @param text       待检查文本
     * @param phase      阶段描述（入站/出站），用于错误信息
     */
    private void runChain(List<Guardrail> guardrails, String text, String phase) {
        for (Guardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.check(text);
            if (!result.allowed()) {
                throw new GuardrailException(
                        phase + "内容被护栏 [" + guardrail.name() + "] 拦截：" + result.reason());
            }
        }
    }

    /**
     * 把消息列表拼接成单段文本。
     *
     * @param messages 消息列表
     * @return 拼接后的文本
     */
    private String concatMessages(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.content() != null) {
                sb.append(message.content()).append('\n');
            }
        }
        return sb.toString();
    }
}
