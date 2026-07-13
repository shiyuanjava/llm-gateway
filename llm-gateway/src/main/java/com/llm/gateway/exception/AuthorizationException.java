package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 鉴权通过但无权访问目标模型（RBAC / allowed-models 限制）。 */
public class AuthorizationException extends GatewayException {

    public AuthorizationException(String message) {
        super(HttpStatus.FORBIDDEN, "permission_error", message);
    }
}
