package com.llm.gateway.auth.admin;

/**
 * 管理端登录主体（JWT 验签通过后放入请求属性）。
 *
 * @param username 管理员用户名
 */
public record AdminPrincipal(String username) {}
