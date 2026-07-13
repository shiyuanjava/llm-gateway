package com.llm.gateway.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import com.llm.gateway.auth.admin.AdminJwtFilter;
import com.llm.gateway.auth.admin.AdminPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 管理面写操作审计过滤器：JWT 过滤器之后执行，记录 {@code /admin/**} 的非 GET 请求
 * （谁 / 何时 / 改了什么 / 来源 IP / 响应码）。登录事件由 {@code AdminAuthService} 自行记录。
 */
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/admin/auth/login";
    /** 请求体缓存上限（字节）：审计 detail 落库前会再截断到 2000 字符，8KB 足够且内存有界。 */
    private static final int MAX_CACHED_BODY_BYTES = 8192;

    private final AdminAuditService auditService;

    public AdminAuditFilter(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 查询不记；登录接口由 AdminAuthService 记（含失败），这里跳过避免重复
        return "GET".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || LOGIN_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Spring Framework 7 起只提供带缓存上限的构造器（无界版本已移除）
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, MAX_CACHED_BODY_BYTES);
        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            AdminPrincipal principal =
                    (AdminPrincipal) wrapped.getAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE);
            // 未通过鉴权的请求（401）不记审计——没有可信身份
            if (principal != null) {
                auditService.record(
                        principal.username(),
                        actionOf(wrapped.getMethod()),
                        resourceOf(wrapped.getRequestURI()),
                        sanitize(new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8)),
                        wrapped.getRemoteAddr(),
                        response.getStatus());
            }
        }
    }

    /** HTTP 方法映射为审计动作。 */
    private String actionOf(String method) {
        return switch (method.toUpperCase()) {
            case "POST" -> "CREATE";
            case "PUT", "PATCH" -> "UPDATE";
            case "DELETE" -> "DELETE";
            default -> method.toUpperCase();
        };
    }

    /** 去掉 /admin/ 前缀作为资源名，如 /admin/api-keys/3 -> api-keys/3。 */
    private String resourceOf(String uri) {
        String resource = uri.startsWith("/admin/") ? uri.substring("/admin/".length()) : uri;
        // reload 语义单独标记
        return resource.isEmpty() ? "admin" : resource;
    }

    /** 脱敏：密码与完整 Key 不落库。 */
    private String sanitize(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        return body
                .replaceAll("(\"password\"\\s*:\\s*\")[^\"]*(\")", "$1***$2")
                .replaceAll("(\"apiKey\"\\s*:\\s*\"sk-[^\"]{4})[^\"]*(\")", "$1***$2");
    }
}
