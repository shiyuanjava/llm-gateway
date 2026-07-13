package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 鉴权失败（缺少或无效的 API Key）。 */
public class AuthenticationException extends GatewayException {

    public AuthenticationException(String message) {
        super(HttpStatus.UNAUTHORIZED, "authentication_error", message);
    }
}
