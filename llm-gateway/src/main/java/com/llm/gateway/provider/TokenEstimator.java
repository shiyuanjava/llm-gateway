package com.llm.gateway.provider;

import java.util.List;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.llm.gateway.api.dto.ChatMessage;

/**
 * Token 估算工具：基于 jtokkit（tiktoken 的 Java 实现）做真实 BPE 分词。
 *
 * <p><strong>只用于估算</strong>：上游未返回 usage 时的兜底、流式中断的用量补记、
 * 路由升级阈值判断。上游返回了真实 usage 一律以上游为准，本地估算绝不覆盖——
 * 对账口径以供应商账单为准（参考 sub2api 的实践）。
 *
 * <p>编码选择：gpt-3.5 与旧版 gpt-4（gpt-4 / gpt-4-*）用 CL100K_BASE，其余模型
 * （gpt-4o/4.1/4.5/o 系、claude、deepseek、mock 及未知）统一用 O200K_BASE——
 * 非 OpenAI 模型无公开 tokenizer，o200k 是估算场景下最接近的通用近似。
 */
public final class TokenEstimator {

    /**
     * OpenAI chat 格式每条消息的结构开销（role/分隔符）经验值。
     * 出处：OpenAI cookbook 的 num_tokens_from_messages——新模型每条消息 3 token，
     * 外加整体 3 token 的回复 priming，取 4 做摊销近似。
     */
    private static final int TOKENS_PER_MESSAGE = 4;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding CL100K = REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    private static final Encoding O200K = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    private TokenEstimator() {}

    /**
     * 估算单段文本的 Token 数（无模型上下文，按 O200K 计）。
     *
     * @param text 文本
     * @return 估算 Token 数，空文本为 0
     */
    public static int estimate(String text) {
        return estimate(null, text);
    }

    /**
     * 估算一组消息的总 Token 数（无模型上下文，按 O200K 计）。
     *
     * @param messages 消息列表
     * @return 估算 Token 数（含每条消息的格式开销）
     */
    public static int estimate(List<ChatMessage> messages) {
        return estimate(null, messages);
    }

    /**
     * 按模型选择编码估算单段文本。
     *
     * @param model 物理模型名（可空，空按 O200K）
     * @param text  文本
     * @return 估算 Token 数，空文本为 0
     */
    public static int estimate(String model, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding(encodingTypeFor(model)).countTokens(text);
    }

    /**
     * 按模型选择编码估算一组消息。
     *
     * @param model    物理模型名（可空）
     * @param messages 消息列表（可空，空按 0 计）
     * @return 估算 Token 数（每条消息加 {@value #TOKENS_PER_MESSAGE} 格式开销）
     */
    public static int estimate(String model, List<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (ChatMessage message : messages) {
            total += TOKENS_PER_MESSAGE + estimate(model, message.content());
        }
        return total;
    }

    /**
     * 模型 → 编码类型（包私有，供测试断言选择规则）。
     *
     * @param model 模型名（可空）
     * @return 编码类型
     */
    static EncodingType encodingTypeFor(String model) {
        if (model != null && (model.startsWith("gpt-3.5") || model.equals("gpt-4") || model.startsWith("gpt-4-"))) {
            return EncodingType.CL100K_BASE;
        }
        return EncodingType.O200K_BASE;
    }

    private static Encoding encoding(EncodingType type) {
        return type == EncodingType.CL100K_BASE ? CL100K : O200K;
    }
}
