package com.llm.gateway.exception;

import org.springframework.http.HttpStatus;

/**
 * 计费 fail-close：请求的路由目标没有可解析的定价，拒绝服务而非静默按 $0 计费。
 *
 * <p>在调上游<strong>之前</strong>抛出——既保证账目诚实，也避免产生无法计费的上游成本。
 */
public class PricingNotConfiguredException extends GatewayException {

    /** @param model 未配置定价的物理模型名 */
    public PricingNotConfiguredException(String model) {
        super(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "pricing_not_configured",
                "模型 [" + model + "] 未配置计费单价，请先在管理端「计费单价」配置（模型名支持尾部 * 通配）");
    }
}
