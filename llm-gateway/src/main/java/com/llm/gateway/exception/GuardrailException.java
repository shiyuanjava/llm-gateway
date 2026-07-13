package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/** 内容安全护栏拦截（入站或出站）。 */
public class GuardrailException extends GatewayException {

    public GuardrailException(String message) {
        super(HttpStatus.BAD_REQUEST, "content_filtered", message);
    }
}
