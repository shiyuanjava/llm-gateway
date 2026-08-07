package com.llm.gateway.ipcontrol;

/** IP 访问控制配置或手动封禁参数不合法。 */
public class IpControlValidationException extends RuntimeException {

    public IpControlValidationException(String message) {
        super(message);
    }
}
