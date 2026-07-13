package com.llm.gateway.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

/**
 * 通用「OpenAI 兼容」供应商适配器。
 *
 * <p>OpenAI、DeepSeek、以及大量国产/开源推理服务都暴露同一套 {@code /chat/completions} +
 * {@code Bearer} 鉴权协议。本适配器把它们统一成一个实现：只需用不同的 name / base-url / api-key
 * 实例化即可（见 {@code ProvidersConfig}）。
 *
 * <p>注意：这里<strong>显式</strong>用 Spring 配置好的 {@link ObjectMapper}（带 SNAKE_CASE、
 * 忽略 null、容忍基本类型 null）来序列化请求、反序列化响应，并以 String 作为 HTTP body——
 * 这样不依赖 RestClient 默认的消息转换器，避免各供应商响应里的字段差异导致解析失败。
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private final String name;
    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    /**
     * @param name         供应商名（如 {@code openai} / {@code deepseek}）
     * @param baseUrl      API 基地址
     * @param apiKey       访问密钥
     * @param objectMapper Spring 配置好的 Jackson 3 ObjectMapper
     * @param http         出站 HTTP 超时配置（可为 null，用默认值）
     */
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, ObjectMapper objectMapper,
                                    GatewayProperties.Http http) {
        this.name = name;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = RestClients.create(baseUrl, http);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException(name + " api-key 未配置");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(request);
            String responseBody = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw new ProviderException(name + " 返回空响应");
            }
            return objectMapper.readValue(responseBody, ChatCompletionResponse.class);
        } catch (ProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("调用 " + name + " 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 流式对话补全：以 SSE 读取上游 {@code /chat/completions} 并逐帧回调。
     *
     * <p>注意超时语义：{@code RestClients} 配置的 HTTP 读超时作用于<strong>每次阻塞读</strong>，
     * 流式期间它实际是「帧间停顿上限」——相邻 SSE 字节间隔超过读超时（默认 30s）即断流抛错，
     * 而非限制整条流的总时长。
     */
    @Override
    public Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException(name + " api-key 未配置");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(request.forStreamingUpstream());
            return restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(requestBody)
                    .exchange((clientRequest, clientResponse) -> {
                        if (clientResponse.getStatusCode().isError()) {
                            String error = new String(clientResponse.getBody().readNBytes(2048), StandardCharsets.UTF_8);
                            throw new ProviderException(name + " 流式调用失败 HTTP "
                                    + clientResponse.getStatusCode() + "：" + truncate(error));
                        }
                        try (SseEventReader reader = new SseEventReader(clientResponse.getBody())) {
                            return readStream(reader, onChunk);
                        }
                    });
        } catch (ProviderException | ClientDisconnectedException | GuardrailException e) {
            throw e; // 断流信号与已分类错误原样上抛（exchange 回调抛出的非受检异常会穿透到这里）
        } catch (Exception e) {
            throw new ProviderException("调用 " + name + " 流式失败：" + e.getMessage(), e);
        }
    }

    /**
     * 消费上游 SSE 事件流：任何帧上出现的 usage 都被网关捕获用于计费（兼容 OpenAI 的独立空
     * choices usage 帧，以及智谱 GLM / 部分 vLLM 把 usage 挂在最后一个内容帧上的做法）；
     * choices 为空的帧（usage 帧、心跳/退化帧）不转发给回调；读到 [DONE] 停止。
     *
     * @param reader  SSE 分帧读取器
     * @param onChunk 内容帧消费者
     * @return 上游给出的用量，未给为 null
     * @throws IOException 读上游失败
     */
    Usage readStream(SseEventReader reader, Consumer<ChatCompletionChunk> onChunk) throws IOException {
        Usage usage = null;
        SseEventReader.SseEvent event;
        while ((event = reader.next()) != null) {
            String data = event.data();
            if ("[DONE]".equals(data.trim())) {
                break;
            }
            ChatCompletionChunk chunk = objectMapper.readValue(data, ChatCompletionChunk.class);
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                continue; // usage/心跳帧，不转发
            }
            onChunk.accept(chunk);
        }
        return usage;
    }

    /** 错误响应体截断到 500 字符，避免日志与异常信息爆炸。 */
    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
