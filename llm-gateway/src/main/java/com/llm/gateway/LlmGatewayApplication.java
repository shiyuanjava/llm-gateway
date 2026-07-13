package com.llm.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import org.mybatis.spring.annotation.MapperScan;

/**
 * LLM Gateway 启动类。
 *
 * <p>本网关是应用层与模型供应商之间的一层<strong>控制面</strong>：业务只需发送一个 OpenAI 兼容的
 * 标准请求，网关负责鉴权、限流、安全护栏、缓存、路由、容错（重试/熔断/Fallback）、计费与可观测。
 * 配置（API Key、路由规则、计费）与记录（请求日志/用量）持久化在 MySQL 中。
 * 代码组织遵循 Harness Engineering 的设计原则：约束优先、可验证、故障假设、少即是多。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.llm.gateway.persistence.mapper")
public class LlmGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmGatewayApplication.class, args);
    }

}
