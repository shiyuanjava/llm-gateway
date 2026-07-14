package com.llm.gateway.provider;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.llm.gateway.config.GatewayProperties;

/**
 * 供应商 RestClient 工厂：统一配置连接/读超时，避免供应商挂起时网关线程被永久占用。
 */
public final class RestClients {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private RestClients() {}

    /**
     * 构造带超时的 RestClient。
     *
     * <p>读超时作用于每次阻塞读：对流式（SSE）响应而言它是「帧间停顿上限」——
     * 相邻字节间隔超过读超时（默认 30s）即断流，而非限制整条流的总时长。
     * 另注意 {@link SimpleClientHttpRequestFactory} 在响应体中途被关闭时，可能为复用
     * keep-alive 连接而继续排空剩余字节，未必立刻硬中断上游。
     *
     * @param baseUrl API 基地址
     * @param http    超时配置（可为 null，用默认值）
     * @return 配置好的 RestClient
     */
    public static RestClient create(String baseUrl, GatewayProperties.Http http) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(
                Duration.ofMillis(http == null ? DEFAULT_CONNECT_TIMEOUT_MS : http.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(http == null ? DEFAULT_READ_TIMEOUT_MS : http.readTimeoutMs()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
