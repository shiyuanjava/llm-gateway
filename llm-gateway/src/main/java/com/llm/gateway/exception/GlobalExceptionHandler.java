package com.llm.gateway.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.llm.gateway.admin.web.R;
import com.llm.gateway.auth.admin.AdminAuthService;

/**
 * 全局异常处理：把网关内部抛出的各类异常统一转换成 OpenAI 风格的错误响应。
 *
 * <p>这是 Harness「L6 恢复」的一部分——确保任何失败都以结构化、对调用方友好的形式返回，
 * 而不是泄漏堆栈或返回 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理所有网关业务异常，按其自带的状态码与错误码返回。
     *
     * @param ex 网关异常
     * @return 统一错误响应
     */
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handleGateway(GatewayException ex) {
        // 4xx 多为调用方问题，记 warn；5xx 为服务端/供应商问题，记 error
        if (ex.status().is5xxServerError()) {
            log.error("网关异常 [{}]: {}", ex.code(), ex.getMessage(), ex);
        } else {
            log.warn("网关异常 [{}]: {}", ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status()).body(ErrorResponse.of(ex.getMessage(), ex.code()));
    }

    /**
     * 处理 Bean Validation 校验失败（如 model 为空、messages 为空）。
     *
     * @param ex 校验异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("请求参数校验失败");
        log.warn("请求校验失败: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(message, "invalid_request_error"));
    }

    /**
     * 处理请求体无法解析（JSON 格式错误、编码错误等）。
     *
     * @param ex 解析异常
     * @return 400 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("请求体无法解析（请确认是合法的 UTF-8 JSON）", "invalid_request_error"));
    }

    /**
     * 管理端登录锁定：HTTP 423。
     */
    @ExceptionHandler(AdminAuthService.LoginLockedException.class)
    public ResponseEntity<R<Void>> handleLoginLocked(AdminAuthService.LoginLockedException e) {
        return ResponseEntity.status(423).body(new R<>(423, e.getMessage(), null));
    }

    /**
     * 客户端在流式响应期间断开：此时响应头已提交（或对端已消失），不写任何响应体，
     * 仅记 debug——这是正常的客户端行为，不是服务端错误，不应产生 ERROR 堆栈。
     *
     * @param ex 断开信号
     */
    @ExceptionHandler(ClientDisconnectedException.class)
    public void handleClientDisconnected(ClientDisconnectedException ex) {
        log.debug("客户端断开：{}", ex.getMessage());
    }

    /**
     * 兜底处理未预期的异常，避免泄漏内部细节。
     *
     * @param ex 未预期异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("未预期的内部错误", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("网关内部错误", "internal_error"));
    }
}
