package com.llm.gateway.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.auth.ApiKeyAuthFilter;
import com.llm.gateway.auth.Principal;
import com.llm.gateway.core.GatewayService;
import com.llm.gateway.exception.AuthenticationException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * 网关对外入口：OpenAI 兼容的 Chat Completions 接口。
 *
 * <p>鉴权已在 {@link ApiKeyAuthFilter} 完成并把 {@link Principal} 放入请求属性；本控制器只负责
 * 取出主体、交给核心编排服务，保持「薄控制器」。
 */
@RestController
public class ChatCompletionController {

    private final GatewayService gatewayService;

    /**
     * @param gatewayService 核心编排服务
     */
    public ChatCompletionController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    /**
     * 处理对话补全请求。{@code stream=true} 时走流式分支：SSE 直写 Servlet 响应并返回 {@code null}
     * （MVC 对 null 返回值不再写响应体）；否则清掉流式提示后走非流式路径。
     *
     * @param request  统一请求体（经 Bean 校验）
     * @param http     HTTP 请求，用于取出鉴权主体
     * @param response HTTP 响应，流式分支 SSE 直写
     * @return 统一响应体（流式分支为 null）
     */
    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse chatCompletions(@Valid @RequestBody ChatCompletionRequest request,
                                                  HttpServletRequest http, HttpServletResponse response) {
        Principal principal = (Principal) http.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            // 正常情况下不会发生（过滤器已拦截），这里做防御性兜底
            throw new AuthenticationException("缺少鉴权主体");
        }
        if (Boolean.TRUE.equals(request.stream())) {
            gatewayService.completeStream(request, principal, response);
            return null; // SSE 已直写并提交；MVC 对 null 返回值不再写响应体
        }
        return gatewayService.complete(request.withoutStreamHints(), principal);
    }
}
