package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 触发限流（单位时间请求数超限）。 */
public class RateLimitExceededException extends GatewayException {

    public RateLimitExceededException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded", message);
    }
}
