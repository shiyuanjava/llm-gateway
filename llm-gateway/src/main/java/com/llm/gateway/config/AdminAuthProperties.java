package com.llm.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * 管理端鉴权配置（前缀 {@code gateway.admin}）。
 *
 * @param jwtSecret          JWT HS256 当前签发密钥，生产必须 ≥32 字符（由 GATEWAY_JWT_SECRET 注入）
 * @param jwtSecretsFallback 历史验签密钥列表（逗号分隔注入）：轮换密钥时旧 token 仍可验签平滑过渡，
 *                           过渡期结束后移除
 * @param tokenTtlMinutes    JWT 有效期（分钟）
 * @param bootstrapUsername  首个管理员用户名（仅 admin_user 表为空时生效）
 * @param bootstrapPassword  首个管理员密码（仅 admin_user 表为空时生效）
 */
@ConfigurationProperties(prefix = "gateway.admin")
public record AdminAuthProperties(
        String jwtSecret,
        List<String> jwtSecretsFallback,
        long tokenTtlMinutes,
        String bootstrapUsername,
        String bootstrapPassword) {

    /** 配置绑定用规范构造器（存在多个构造器时需显式指定）。 */
    @ConstructorBinding
    public AdminAuthProperties {}

    /** 兼容旧签名的便捷构造（测试用）：无历史密钥。 */
    public AdminAuthProperties(
            String jwtSecret, long tokenTtlMinutes, String bootstrapUsername, String bootstrapPassword) {
        this(jwtSecret, List.of(), tokenTtlMinutes, bootstrapUsername, bootstrapPassword);
    }
}
