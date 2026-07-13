package com.llm.gateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatMessage;

/**
 * 精确缓存键的生成工具。
 *
 * <p>键由「能影响输出的全部要素」组成：model + messages + temperature + top_p + max_tokens，
 * 取其规范化字符串的 SHA-256。文章特别提醒：system prompt、采样参数都要纳入键，否则会错误命中。
 */
public final class CacheKey {

    private CacheKey() {
    }

    /**
     * 为请求生成精确缓存键。
     *
     * @param request 请求
     * @return 十六进制 SHA-256 字符串
     */
    public static String of(ChatCompletionRequest request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("model=").append(request.model()).append('|');
        canonical.append("temperature=").append(request.temperature()).append('|');
        canonical.append("topP=").append(request.topP()).append('|');
        canonical.append("maxTokens=").append(request.maxTokens()).append('|');
        canonical.append("messages=");
        for (ChatMessage message : request.messages()) {
            canonical.append('[').append(message.role()).append(':').append(message.content()).append(']');
        }
        return sha256(canonical.toString());
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     *
     * @param input 输入字符串
     * @return 十六进制摘要
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，理论上不会发生
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
