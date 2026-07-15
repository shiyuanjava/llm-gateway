package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 调用具体供应商失败（HTTP 错误、超时、解析失败等）。
 *
 * <p>通过 {@code retryable} 区分两类故障：瞬时故障（5xx、429、超时、连接失败）可重试/可降级；
 * 确定性错误（其余 4xx、密钥未配置、未知供应商等配置问题）重试注定同样失败，应立即换目标或上抛，
 * 且不应计入熔断器的失败统计（避免污染对供应商健康度的判断）。
 */
public class ProviderException extends GatewayException {

    private final boolean retryable;

    public ProviderException(String message) {
        this(message, null, true);
    }

    public ProviderException(String message, Throwable cause) {
        this(message, cause, true);
    }

    /**
     * @param message   错误信息
     * @param cause     根因（可为 null）
     * @param retryable 是否为可重试的瞬时故障
     */
    public ProviderException(String message, Throwable cause, boolean retryable) {
        super(HttpStatus.BAD_GATEWAY, "provider_error", message);
        this.retryable = retryable;
        if (cause != null) {
            initCause(cause);
        }
    }

    /** 构造不可重试（确定性）的供应商错误。 */
    public static ProviderException nonRetryable(String message) {
        return new ProviderException(message, null, false);
    }

    /**
     * 按上游 HTTP 状态码判断是否值得重试：5xx、429（限流）、408（请求超时）为瞬时故障，
     * 其余 4xx（400/401/403/404/422…）是请求或配置层面的确定性错误。
     *
     * @param statusCode 上游 HTTP 状态码
     * @return true 表示可重试
     */
    public static boolean isRetryableStatus(int statusCode) {
        return statusCode >= 500 || statusCode == 429 || statusCode == 408;
    }

    /** @return 是否为可重试的瞬时故障 */
    public boolean retryable() {
        return retryable;
    }
}
