package com.llm.gateway.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.llm.gateway.admin.web.AdminApiException;
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
    public ResponseEntity<Object> handleGateway(GatewayException ex, HttpServletRequest request) {
        // 4xx 多为调用方问题，记 warn；5xx 为服务端/供应商问题，记 error
        if (ex.status().is5xxServerError()) {
            log.error("网关异常 [{}]: {}", ex.code(), ex.getMessage(), ex);
        } else {
            log.warn("网关异常 [{}]: {}", ex.code(), ex.getMessage());
        }
        return error(request, ex.status(), ex.getMessage(), ex.code());
    }

    /**
     * 处理 Bean Validation 校验失败（如 model 为空、messages 为空）。
     *
     * @param ex 校验异常
     * @return 400 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("请求参数校验失败");
        log.warn("请求校验失败: {}", message);
        return error(request, HttpStatus.BAD_REQUEST, message, "invalid_request_error");
    }

    /**
     * 处理请求体无法解析（JSON 格式错误、编码错误等）。
     *
     * @param ex 解析异常
     * @return 400 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("请求体无法解析: {}", ex.getMessage());
        return error(request, HttpStatus.BAD_REQUEST, "请求体无法解析（请确认是合法的 UTF-8 JSON）", "invalid_request_error");
    }

    /** Management-domain business failure with the stable {@code R} envelope. */
    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<Object> handleAdminApi(AdminApiException ex, HttpServletRequest request) {
        return error(request, ex.status(), ex.getMessage(), adminCode(ex.status()));
    }

    /** Bean validation failures not attached to a request body (for example, query parameters). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .sorted()
                .reduce((left, right) -> left + "; " + right)
                .orElse("请求参数校验失败");
        return error(request, HttpStatus.BAD_REQUEST, message, "invalid_request_error");
    }

    /** Type conversion failures for path/query parameters. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, ex.getName() + " 参数格式错误", "invalid_request_error");
    }

    /** Database uniqueness/constraint failures are conflicts, not opaque 500s. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("数据约束冲突: {}", ex.getClass().getSimpleName());
        return error(request, HttpStatus.CONFLICT, "数据已存在或违反唯一性约束", "conflict");
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
     * 未知路径（无对应 handler 且无静态资源）：404 而非落进兜底的 500。
     * 扫描器会高频探测随机路径，仅记 debug 避免刷日志。
     *
     * @param ex 未知路径信号
     * @return 404 错误响应
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        log.debug("未知路径: /{}", ex.getResourcePath());
        return error(request, HttpStatus.NOT_FOUND, "请求路径不存在", "not_found");
    }

    /**
     * 兜底处理未预期的异常，避免泄漏内部细节。
     *
     * @param ex 未预期异常
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期的内部错误", ex);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "网关内部错误", "internal_error");
    }

    private ResponseEntity<Object> error(
            HttpServletRequest request, HttpStatus status, String message, String openAiCode) {
        Object body = isAdmin(request) ? R.error(status.value(), message) : ErrorResponse.of(message, openAiCode);
        return ResponseEntity.status(status).body(body);
    }

    private boolean isAdmin(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        return "/admin".equals(uri) || (uri != null && uri.startsWith("/admin/"));
    }

    private String adminCode(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "invalid_request_error";
            case NOT_FOUND -> "not_found";
            case CONFLICT -> "conflict";
            case SERVICE_UNAVAILABLE -> "service_unavailable";
            default -> "admin_error";
        };
    }
}
