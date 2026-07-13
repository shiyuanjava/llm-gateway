package com.llm.gateway.auth.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.llm.gateway.admin.web.R;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 管理端登录接口（{@code /admin/auth/**}）。login 免鉴权（过滤器放行），me 需登录。
 */
@RestController
@RequestMapping("/admin/auth")
public class AdminAuthController {

    /** 登录请求体。 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** 登录响应:JWT、用户名、过期时刻(epoch 毫秒,前端据此在过期后直接跳登录)。 */
    public record LoginResponse(String token, String username, long expiresAt) {
    }

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。
     *
     * @param body    用户名 + 密码
     * @param request 用于取来源 IP
     * @return JWT、用户名与过期时刻
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AdminAuthService.LoginResult result =
                authService.login(body.username(), body.password(), request.getRemoteAddr());
        return R.ok(new LoginResponse(result.token(), body.username(), result.expiresAtMillis()));
    }

    /**
     * 当前登录用户（前端启动时校验 token 有效性用）。
     *
     * @param principal 过滤器注入的主体
     * @return 用户名
     */
    @GetMapping("/me")
    public R<String> me(@RequestAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE) AdminPrincipal principal) {
        return R.ok(principal.username());
    }
}
