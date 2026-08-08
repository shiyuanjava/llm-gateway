package com.llm.gateway.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatMessage;

/** Generates collision-resistant exact-cache keys from all output-affecting request fields. */
public final class CacheKey {

    private CacheKey() {}

    /**
     * Encodes nullable strings with an explicit null marker and byte length before hashing. This
     * makes message boundaries unambiguous even when content contains legacy delimiters.
     *
     * <p>跨租户共享口径的键（{@code tenant == null}）。仅供显式配置 {@code gateway.cache.scope=global}
     * 时使用；默认应走 {@link #of(ChatCompletionRequest, String)} 的租户分区键。
     */
    public static String of(ChatCompletionRequest request) {
        return of(request, null);
    }

    /**
     * 生成租户分区的精确缓存键。租户参与摘要，租户 A 的条目不会被租户 B 读到。
     *
     * @param request 请求
     * @param tenant  租户标识；{@code null} 表示跨租户共享口径
     * @return 十六进制 SHA-256 摘要
     */
    public static String of(ChatCompletionRequest request, String tenant) {
        MessageDigest digest = sha256Digest();
        updateNullable(digest, tenant);
        updateNullable(digest, request.model());
        updateNullable(
                digest,
                request.temperature() == null ? null : request.temperature().toString());
        updateNullable(digest, request.topP() == null ? null : request.topP().toString());
        updateNullable(
                digest, request.maxTokens() == null ? null : request.maxTokens().toString());
        updateInt(digest, request.messages().size());
        for (ChatMessage message : request.messages()) {
            updateNullable(digest, message.role());
            updateNullable(digest, message.content());
        }
        return hex(digest.digest());
    }

    private static void updateNullable(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update((byte) 1);
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }
}
