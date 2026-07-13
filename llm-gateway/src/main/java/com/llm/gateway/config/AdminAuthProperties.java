package com.llm.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端鉴权配置（前缀 {@code gateway.admin}）。
 *
 * @param jwtSecret         JWT HS256 密钥，生产必须 ≥32 字符（由 GATEWAY_JWT_SECRET 注入）
 * @param tokenTtlMinutes   JWT 有效期（分钟）
 * @param bootstrapUsername 首个管理员用户名（仅 admin_user 表为空时生效）
 * @param bootstrapPassword 首个管理员密码（仅 admin_user 表为空时生效）
 */
@ConfigurationProperties(prefix = "gateway.admin")
public record AdminAuthProperties(
        String jwtSecret,
        long tokenTtlMinutes,
        String bootstrapUsername,
        String bootstrapPassword) {
}
