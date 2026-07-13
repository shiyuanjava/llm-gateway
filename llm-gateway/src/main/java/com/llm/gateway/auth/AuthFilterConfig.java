package com.llm.gateway.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.llm.gateway.audit.AdminAuditFilter;
import com.llm.gateway.audit.AdminAuditService;
import com.llm.gateway.auth.admin.AdminAuthService;
import com.llm.gateway.auth.admin.AdminJwtFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * 鉴权过滤器注册配置：{@code /v1/*} 走 API Key 认证，{@code /admin/*} 走管理端 JWT 认证 + 写操作审计。
 *
 * <p>全局 Filter 顺序:TraceIdFilter(HIGHEST)→ CorsFilter(+10)→ ApiKey(+20)→ AdminJwt(+30)→ 审计(+40)。
 *
 * <p>用 {@link FilterRegistrationBean} 显式注册而非把过滤器声明为 Bean，是为了精确控制
 * 作用路径，避免 Actuator 健康检查等端点也被强制鉴权。
 */
@Configuration
public class AuthFilterConfig {

    /**
     * 注册 API Key 鉴权过滤器。
     *
     * @param apiKeyService API Key 服务
     * @param objectMapper  JSON 序列化器
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(
            ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new ApiKeyAuthFilter(apiKeyService, objectMapper));
        registration.addUrlPatterns("/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        registration.setName("apiKeyAuthFilter");
        return registration;
    }

    /**
     * 注册管理端 JWT 鉴权过滤器（/admin/**，登录接口在过滤器内放行）。
     *
     * @param authService  管理端鉴权服务
     * @param objectMapper JSON 序列化器
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<AdminJwtFilter> adminJwtFilter(
            AdminAuthService authService, ObjectMapper objectMapper) {
        FilterRegistrationBean<AdminJwtFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminJwtFilter(authService, objectMapper));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        registration.setName("adminJwtFilter");
        return registration;
    }

    /**
     * 注册管理面审计过滤器（JWT 之后执行，只记写操作）。
     *
     * @param auditService 审计服务
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<AdminAuditFilter> adminAuditFilter(AdminAuditService auditService) {
        FilterRegistrationBean<AdminAuditFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AdminAuditFilter(auditService));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);
        registration.setName("adminAuditFilter");
        return registration;
    }
}
