package com.llm.gateway.ipcontrol;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;

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

/** 在 API Key 鉴权之前检查客户端 IP，避免无效 Key/扫描流量绕过频率统计。 */
public class IpBlockFilter extends OncePerRequestFilter {

    private final IpBlockService blockService;
    private final ObjectMapper objectMapper;

    public IpBlockFilter(IpBlockService blockService, ObjectMapper objectMapper) {
        this.blockService = blockService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        IpBlockService.BlockDecision decision = blockService.evaluate(request.getRemoteAddr());
        if (!decision.blocked()) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean manual = IpBlockService.SOURCE_MANUAL.equals(decision.source());
        response.setStatus((manual ? HttpStatus.FORBIDDEN : HttpStatus.TOO_MANY_REQUESTS).value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (decision.blockedUntil() != null) {
            long retryAfter = Math.max(
                    1,
                    Duration.between(LocalDateTime.now(), decision.blockedUntil())
                            .toSeconds());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            response.setHeader("X-Blocked-Until", decision.blockedUntil().toString());
        }
        String suffix = decision.permanent() ? "（永久）" : "，请在封禁到期后重试";
        ErrorResponse body = ErrorResponse.of("当前 IP 已被禁用：" + decision.reason() + suffix, "ip_blocked");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
