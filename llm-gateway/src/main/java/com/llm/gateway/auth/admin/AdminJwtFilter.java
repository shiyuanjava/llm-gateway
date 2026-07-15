package com.llm.gateway.auth.admin;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.llm.gateway.admin.web.R;

import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 JWT 鉴权过滤器：拦截 {@code /admin/**}，放行登录接口与 CORS 预检；
 * 验签通过把 {@link AdminPrincipal} 放入请求属性，失败返回 401（R 包装，前端拦截器识别跳登录）。
 */
public class AdminJwtFilter extends OncePerRequestFilter {

    /** 请求属性名：登录主体。 */
    public static final String ADMIN_PRINCIPAL_ATTRIBUTE = "gateway.adminPrincipal";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String LOGIN_PATH = "/admin/auth/login";

    private final AdminAuthService authService;
    private final ObjectMapper objectMapper;

    public AdminJwtFilter(AdminAuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 放行登录接口与 CORS 预检
        return LOGIN_PATH.equals(request.getRequestURI()) || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith(BEARER_PREFIX)
                ? header.substring(BEARER_PREFIX.length()).trim()
                : null;
        Optional<AdminPrincipal> principal = authService.verify(token);
        if (principal.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), new R<Void>(401, "未登录或登录已过期", null));
            return;
        }
        request.setAttribute(ADMIN_PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }
}
