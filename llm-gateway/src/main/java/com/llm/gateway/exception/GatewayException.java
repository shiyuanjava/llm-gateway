package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 网关业务异常的基类。
 *
 * <p>每个异常自带 HTTP 状态码与错误码，{@code GlobalExceptionHandler} 据此组装统一的错误响应。
 * 这体现 Harness 的「故障假设」：把失败显式建模为可识别、可分类、对调用方友好的信号。
 */
public abstract class GatewayException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /**
     * @param status  对应的 HTTP 状态码
     * @param code    机器可读的错误码
     * @param message 人类可读的错误信息
     */
    protected GatewayException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** @return HTTP 状态码 */
    public HttpStatus status() {
        return status;
    }

    /** @return 错误码 */
    public String code() {
        return code;
    }
}
