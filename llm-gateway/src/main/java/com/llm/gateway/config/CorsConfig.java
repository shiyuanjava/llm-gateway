package com.llm.gateway.config;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import com.llm.gateway.observability.TraceIdFilter;

/**
 * 浏览器入口 CORS:用 Servlet 层 {@link CorsFilter}(注册在鉴权过滤器之前)而非 MVC 级配置,
 * 使鉴权过滤器直写的 401 响应也带 CORS 头;预检 OPTIONS 亦在此处理。
 *
 * <p>白名单来自 {@code gateway.cors.allowed-origins}(逗号分隔):开发默认放行 Vite dev server;
 * 生产 prod profile 默认为空(nginx 同源反代,浏览器不发跨域请求),分域部署时用
 * {@code GATEWAY_CORS_ALLOWED_ORIGINS} 打开。控制台既访问 {@code /admin/**},也会在 Playground
 * 里访问 {@code /v1/chat/completions},因此两段链路使用同一来源白名单。为空时不注册任何 CORS 映射。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> gatewayCorsFilter(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = properties.allowedOrigins();
        if (!origins.isEmpty()) {
            source.registerCorsConfiguration(
                    "/admin/**",
                    corsConfiguration(
                            origins, List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"), properties.maxAgeSeconds()));
            source.registerCorsConfiguration(
                    "/v1/**", corsConfiguration(origins, List.of("POST", "OPTIONS"), properties.maxAgeSeconds()));
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/admin/*", "/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("gatewayCorsFilter");
        return registration;
    }

    private static CorsConfiguration corsConfiguration(
            List<String> origins, List<String> methods, long maxAgeSeconds) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(methods);
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", TraceIdFilter.HEADER));
        config.setExposedHeaders(List.of(TraceIdFilter.HEADER));
        config.setMaxAge(maxAgeSeconds);
        return config;
    }
}
