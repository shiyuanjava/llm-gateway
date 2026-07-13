package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 路由链上所有目标都不可用（全部失败或被熔断），无可用供应商。 */
public class NoProviderAvailableException extends GatewayException {

    public NoProviderAvailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "no_provider_available", message);
    }

    public NoProviderAvailableException(String message, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "no_provider_available", message);
        initCause(cause);
    }
}
