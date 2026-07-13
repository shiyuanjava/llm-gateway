package com.llm.gateway.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 链路追踪过滤器(所有 Filter 之前):把 traceId 放入 MDC 供日志 pattern 输出,并回写响应头。
 *
 * <p>优先复用请求头 {@code X-Request-Id}(限长 64、仅字母数字下划线连字符,防日志注入),
 * 缺失或非法时自生成。{@code GatewayService} 用同一 traceId 作为 request_log.request_id,
 * 应用日志、响应头、审计表三者可互查。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key,与 logback pattern 的 %X{traceId:-} 对应。 */
    public static final String MDC_KEY = "traceId";
    /** 透传/回写的请求头名。 */
    public static final String HEADER = "X-Request-Id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : newTraceId();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** @return 16 位短 ID(UUID 去横线取前 16 位) */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
