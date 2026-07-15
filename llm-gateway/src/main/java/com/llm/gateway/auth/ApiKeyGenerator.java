package com.llm.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * API Key 生成与哈希工具：服务端生成 {@code sk-gw-} + 32 位随机 hex；
 * 库中只存 SHA-256 哈希与展示前缀，明文仅在创建响应中出现一次。
 */
public final class ApiKeyGenerator {

    /** 生成 Key 的固定前缀。 */
    public static final String KEY_PREFIX = "sk-gw-";
    /** 展示用前缀长度（sk-gw- + 6 位随机）。 */
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private static final int RANDOM_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyGenerator() {}

    /** @return 新的随机 API Key（sk-gw- + 32 位 hex） */
    public static String generate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * SHA-256（hex 小写），与 MySQL {@code SHA2(x, 256)} 输出一致。
     *
     * @param key 明文 Key
     * @return 64 位 hex 哈希
     */
    public static String sha256(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 列表展示用前缀（前 12 字符）。
     *
     * @param key 明文 Key
     * @return 前缀
     */
    public static String prefixOf(String key) {
        return key.length() <= DISPLAY_PREFIX_LENGTH ? key : key.substring(0, DISPLAY_PREFIX_LENGTH);
    }
}
