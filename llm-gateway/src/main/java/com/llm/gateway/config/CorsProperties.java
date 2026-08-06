package com.llm.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 浏览器入口 CORS 配置。
 *
 * @param allowedOrigins 允许访问管理面与 Playground API 的前端 Origin/Origin Pattern
 * @param maxAgeSeconds  浏览器预检结果缓存秒数
 */
@ConfigurationProperties(prefix = "gateway.cors")
public record CorsProperties(List<String> allowedOrigins, long maxAgeSeconds) {

    private static final long DEFAULT_MAX_AGE_SECONDS = 3600;

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(origin -> origin != null && !origin.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (maxAgeSeconds <= 0) {
            maxAgeSeconds = DEFAULT_MAX_AGE_SECONDS;
        }
    }
}
