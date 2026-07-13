package com.llm.gateway.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性过滤器注册:TraceIdFilter 排在最前(CORS 与鉴权过滤器之前),
 * 保证过滤器直写的 401/403 响应对应的日志也带 traceId。
 */
@Configuration
public class ObservabilityFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
