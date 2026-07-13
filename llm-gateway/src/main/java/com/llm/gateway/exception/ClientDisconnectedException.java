package com.llm.gateway.exception;

/**
 * 客户端在流式响应期间断开连接。
 *
 * <p>不继承 {@link GatewayException}：对端已消失，不存在「给客户端的错误响应」语义；
 * 上层据此中止上游读取、尽力落审计，且<strong>不计入</strong>供应商熔断统计。
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
