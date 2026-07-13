package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 调用具体供应商失败（HTTP 错误、超时、解析失败等）。属于可重试/可降级的故障。 */
public class ProviderException extends GatewayException {

    public ProviderException(String message) {
        super(HttpStatus.BAD_GATEWAY, "provider_error", message);
    }

    public ProviderException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "provider_error", message);
        initCause(cause);
    }
}
