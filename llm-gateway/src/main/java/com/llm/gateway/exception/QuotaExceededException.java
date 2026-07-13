package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 租户累计 Token 配额耗尽。 */
public class QuotaExceededException extends GatewayException {

    public QuotaExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", message);
    }
}
