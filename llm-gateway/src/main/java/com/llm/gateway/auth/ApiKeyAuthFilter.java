package com.llm.gateway.auth;

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

import com.llm.gateway.exception.ErrorResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * API Key 鉴权过滤器：在请求进入业务流水线之前完成认证。
 *
 * <p>读取 {@code Authorization: Bearer <key>}（兼容 OpenAI SDK 默认行为），认证通过则把
 * {@link Principal} 放入请求属性供下游使用；失败则直接返回 401，请求不再继续。
 *
 * <p>把鉴权放在网关入口（而非各业务应用里）符合 Harness「旁路不可绕过 + 集中审计」的理念。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** 请求属性名：认证通过的主体存放于此，供 Controller 取用。 */
    public static final String PRINCIPAL_ATTRIBUTE = "gateway.principal";

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;

    /**
     * @param apiKeyService API Key 服务
     * @param objectMapper  用于序列化 401 错误响应
     */
    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ObjectMapper objectMapper) {
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String apiKey = extractApiKey(request);
        Optional<Principal> principal = apiKeyService.authenticate(apiKey);
        if (principal.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
        filterChain.doFilter(request, response);
    }

    /**
     * 从 {@code Authorization} 头提取 Bearer Token。
     *
     * @param request HTTP 请求
     * @return API Key，缺失时为 null
     */
    private String extractApiKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * 写出 401 JSON 错误响应。
     *
     * @param response HTTP 响应
     * @throws IOException 写出失败
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ErrorResponse body = ErrorResponse.of("缺少或无效的 API Key", "authentication_error");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
