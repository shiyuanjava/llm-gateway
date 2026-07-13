package com.llm.gateway.exception;

/**
 * 统一错误响应体，沿用 OpenAI 的错误结构 {@code {"error": {...}}}，便于现有 OpenAI SDK 直接解析。
 *
 * @param error 错误详情
 */
public record ErrorResponse(ErrorDetail error) {

    /**
     * 错误详情。
     *
     * @param message 人类可读信息
     * @param type    错误类型/错误码
     * @param code    细分错误码（这里与 type 复用）
     */
    public record ErrorDetail(String message, String type, String code) {
    }

    /**
     * 构造一个错误响应。
     *
     * @param message 错误信息
     * @param code    错误码
     * @return 错误响应
     */
    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse(new ErrorDetail(message, code, code));
    }
}
