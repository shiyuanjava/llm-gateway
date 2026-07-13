package com.llm.gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 管理端 CORS:用 Servlet 层 {@link CorsFilter}(注册在鉴权过滤器之前)而非 MVC 级配置,
 * 使鉴权过滤器直写的 401 响应也带 CORS 头;预检 OPTIONS 亦在此处理。
 *
 * <p>白名单来自 {@code gateway.admin.allowed-origins}(逗号分隔):开发默认放行 Vite dev server;
 * 生产 prod profile 默认为空(nginx 同源反代,浏览器不发跨域请求),分域部署时用
 * {@code GATEWAY_ADMIN_ALLOWED_ORIGINS} 打开。为空时不注册任何 CORS 映射。
 * {@code /v1/**} 是服务端对服务端 API,不配 CORS。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> adminCorsFilter(
            @Value("${gateway.admin.allowed-origins:}") List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOriginPatterns(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            source.registerCorsConfiguration("/admin/**", config);
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminCorsFilter");
        return registration;
    }
}
