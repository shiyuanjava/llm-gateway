package com.llm.gateway.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

/**
 * Anthropic 供应商适配器。
 *
 * <p>Anthropic 的 Messages API 与 OpenAI 协议不同（system 单独传、content 为块数组、用 x-api-key 头、
 * max_tokens 必填、用量字段为 input_tokens/output_tokens）。本适配器负责双向翻译，把差异封装在
 * 网关内部，业务侧无感知——这正是统一接入层的价值。
 *
 * <p>序列化/反序列化显式使用 Spring 配置好的 {@link ObjectMapper}（SNAKE_CASE、容忍 null），
 * 以 String 作为 HTTP body，避免依赖 RestClient 默认转换器。
 */
@Component
public class AnthropicProvider implements LlmProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final RestClient restClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    /** 单条流式响应的总时长上限毫秒（wall-clock），防慢速流无限占用连接。 */
    private final long streamMaxDurationMs;

    /**
     * @param properties   网关配置，提供 Anthropic 的 base-url 与密钥
     * @param objectMapper Spring 配置好的 Jackson 3 ObjectMapper
     */
    public AnthropicProvider(GatewayProperties properties, ObjectMapper objectMapper) {
        GatewayProperties.ProviderConfig config =
                properties.providers() == null ? null : properties.providers().get(name());
        this.apiKey = config == null ? null : config.apiKey();
        String baseUrl = config == null ? "https://api.anthropic.com/v1" : config.baseUrl();
        this.objectMapper = objectMapper;
        this.restClient = RestClients.create(baseUrl, properties.http());
        this.streamMaxDurationMs =
                properties.http() == null ? 300_000L : properties.http().streamMaxDurationMs();
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public ChatCompletionResponse chat(ChatCompletionRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw ProviderException.nonRetryable("Anthropic api-key 未配置（设置 ANTHROPIC_API_KEY 环境变量）");
        }
        try {
            String requestBody = objectMapper.writeValueAsString(toAnthropicBody(request));
            String responseBody = restClient
                    .post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                throw new ProviderException("Anthropic 返回空响应");
            }
            return fromAnthropicResponse(
                    objectMapper.readValue(responseBody, AnthropicResponse.class), request.model());
        } catch (ProviderException e) {
            throw e;
        } catch (RestClientResponseException e) {
            // 按上游状态码区分瞬时故障（5xx/429/408，可重试）与确定性错误（其余 4xx，不重试）
            throw new ProviderException(
                    "调用 Anthropic 失败 HTTP " + e.getStatusCode() + "：" + truncate(e.getResponseBodyAsString()),
                    e,
                    ProviderException.isRetryableStatus(e.getStatusCode().value()));
        } catch (Exception e) {
            throw new ProviderException("调用 Anthropic 失败：" + e.getMessage(), e);
        }
    }

    /**
     * 流式对话补全：以 SSE 读取 Anthropic {@code /messages}（stream=true）并翻译成 OpenAI chunk 逐帧回调。
     *
     * <p>注意超时语义：{@code RestClients} 配置的 HTTP 读超时作用于<strong>每次阻塞读</strong>，
     * 流式期间它实际是「帧间停顿上限」——相邻 SSE 字节间隔超过读超时（默认 30s）即断流抛错，
     * 而非限制整条流的总时长。
     */
    @Override
    public Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw ProviderException.nonRetryable("Anthropic api-key 未配置（设置 ANTHROPIC_API_KEY 环境变量）");
        }
        try {
            Map<String, Object> body = toAnthropicBody(request);
            body.put("stream", true);
            String requestBody = objectMapper.writeValueAsString(body);
            return restClient
                    .post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(requestBody)
                    .exchange((clientRequest, clientResponse) -> {
                        if (clientResponse.getStatusCode().isError()) {
                            String error =
                                    new String(clientResponse.getBody().readNBytes(2048), StandardCharsets.UTF_8);
                            throw new ProviderException(
                                    "Anthropic 流式调用失败 HTTP " + clientResponse.getStatusCode() + "：" + truncate(error),
                                    null,
                                    ProviderException.isRetryableStatus(
                                            clientResponse.getStatusCode().value()));
                        }
                        try (SseEventReader reader = new SseEventReader(clientResponse.getBody())) {
                            return readAnthropicStream(reader, request.model(), onChunk);
                        }
                    });
        } catch (ProviderException | ClientDisconnectedException | GuardrailException e) {
            throw e; // 断流信号与已分类错误原样上抛（exchange 回调抛出的非受检异常会穿透到这里）
        } catch (Exception e) {
            throw new ProviderException("调用 Anthropic 流式失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把 Anthropic 事件流翻译成 OpenAI chunk：message_start→首帧（取 id、input_tokens 与缓存字段，
     * 并以其 output_tokens 作为已知下限），content_block_delta→内容帧，message_delta→记录 stop_reason
     * 与 output_tokens（usage 若携带累计 input/cache 亦覆盖），message_stop→结束帧；
     * ping/content_block_start/content_block_stop 忽略；
     * error→ProviderException。流意外截断时补发结束帧；此时若输出用量从未获知则返回 null，
     * 交由调用方估算——绝不把 0 当权威值上报导致少计费。
     *
     * @param reader  SSE 分帧读取器
     * @param model   请求的物理模型名（chunk 的 model 字段）
     * @param onChunk 内容帧消费者
     * @return 用量（prompt 含缓存的网关口径；input/缓存来自 message_start，output 来自最后携带 usage 的事件）；
     *         流截断且输出用量未知时为 null
     * @throws IOException 读上游失败
     */
    Usage readAnthropicStream(SseEventReader reader, String model, Consumer<ChatCompletionChunk> onChunk)
            throws IOException {
        String id = "chatcmpl-anthropic";
        long created = Instant.now().getEpochSecond();
        int inputTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;
        Integer outputTokens = null;
        String finishReason = "stop";
        boolean firstSent = false;

        SseEventReader.SseEvent event;
        long deadlineNanos = System.nanoTime() + streamMaxDurationMs * 1_000_000L;
        while ((event = reader.next()) != null) {
            if (System.nanoTime() > deadlineNanos) {
                // wall-clock 上限:读超时只限帧间停顿,慢速流可绕过;超总时长主动断流(非供应商故障,不计熔断)
                throw ProviderException.nonRetryable("Anthropic 流式响应超过总时长上限 " + streamMaxDurationMs + "ms，主动断流");
            }
            AnthropicStreamEvent parsed = objectMapper.readValue(event.data(), AnthropicStreamEvent.class);
            switch (parsed.type() == null ? "" : parsed.type()) {
                case "message_start" -> {
                    if (parsed.message() != null) {
                        if (parsed.message().id() != null) {
                            id = parsed.message().id();
                        }
                        if (parsed.message().usage() != null) {
                            if (parsed.message().usage().inputTokens() != null) {
                                inputTokens = parsed.message().usage().inputTokens();
                            }
                            if (parsed.message().usage().cacheCreationInputTokens() != null) {
                                cacheCreationTokens = parsed.message().usage().cacheCreationInputTokens();
                            }
                            if (parsed.message().usage().cacheReadInputTokens() != null) {
                                cacheReadTokens = parsed.message().usage().cacheReadInputTokens();
                            }
                            if (parsed.message().usage().outputTokens() != null) {
                                outputTokens = parsed.message().usage().outputTokens();
                            }
                        }
                    }
                    onChunk.accept(ChatCompletionChunk.first(id, created, model));
                    firstSent = true;
                }
                case "content_block_delta" -> {
                    if (parsed.delta() != null
                            && "text_delta".equals(parsed.delta().type())
                            && parsed.delta().text() != null) {
                        onChunk.accept(ChatCompletionChunk.content(
                                id, created, model, parsed.delta().text()));
                    }
                }
                case "message_delta" -> {
                    if (parsed.delta() != null && parsed.delta().stopReason() != null) {
                        finishReason = mapStopReason(parsed.delta().stopReason());
                    }
                    if (parsed.usage() != null) {
                        if (parsed.usage().inputTokens() != null) {
                            inputTokens = parsed.usage().inputTokens();
                        }
                        if (parsed.usage().cacheCreationInputTokens() != null) {
                            cacheCreationTokens = parsed.usage().cacheCreationInputTokens();
                        }
                        if (parsed.usage().cacheReadInputTokens() != null) {
                            cacheReadTokens = parsed.usage().cacheReadInputTokens();
                        }
                        if (parsed.usage().outputTokens() != null) {
                            outputTokens = parsed.usage().outputTokens();
                        }
                    }
                }
                case "message_stop" -> {
                    if (!firstSent) {
                        onChunk.accept(ChatCompletionChunk.first(id, created, model)); // 缺首帧兜底
                    }
                    onChunk.accept(ChatCompletionChunk.finish(id, created, model, finishReason));
                    return toGatewayUsage(
                            inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens == null ? 0 : outputTokens);
                }
                case "error" -> throw new ProviderException("Anthropic 流式返回错误：" + truncate(event.data()));
                default -> {
                    /* ping / content_block_start / content_block_stop 忽略 */
                }
            }
        }
        if (firstSent) {
            onChunk.accept(ChatCompletionChunk.finish(id, created, model, finishReason)); // 截断兜底
        }
        return outputTokens == null
                ? null
                : toGatewayUsage(inputTokens, cacheCreationTokens, cacheReadTokens, outputTokens);
    }

    /**
     * 加法归一化：Anthropic 口径（input 不含缓存）→ 网关口径（prompt 含缓存，缓存明细随行）。
     *
     * @param input         输入 Token（不含缓存）
     * @param cacheCreation 缓存写 Token
     * @param cacheRead     缓存读 Token
     * @param output        输出 Token
     * @return 网关口径用量
     */
    private static Usage toGatewayUsage(int input, int cacheCreation, int cacheRead, int output) {
        return Usage.of(input + cacheCreation + cacheRead, output, cacheRead, cacheCreation);
    }

    /**
     * 把统一请求翻译成 Anthropic Messages API 请求体。
     *
     * @param request 统一请求
     * @return Anthropic 请求体
     */
    private Map<String, Object> toAnthropicBody(ChatCompletionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens());

        StringBuilder system = new StringBuilder();
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if ("system".equals(message.role())) {
                system.append(message.content()).append('\n');
            } else {
                messages.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        body.put("messages", messages);
        if (!system.isEmpty()) {
            body.put("system", system.toString().trim());
        }
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        return body;
    }

    /**
     * 把 Anthropic 响应翻译回统一响应。
     *
     * @param response Anthropic 响应
     * @param model    物理模型名
     * @return 统一响应
     */
    private ChatCompletionResponse fromAnthropicResponse(AnthropicResponse response, String model) {
        StringBuilder text = new StringBuilder();
        if (response.content() != null) {
            for (AnthropicResponse.ContentBlock block : response.content()) {
                if ("text".equals(block.type()) && block.text() != null) {
                    text.append(block.text());
                }
            }
        }
        String id = response.id() == null ? "chatcmpl-anthropic" : response.id();
        String finishReason = mapStopReason(response.stopReason() == null ? "stop" : response.stopReason());
        Usage usage =
                response.usage() == null ? Usage.of(0, 0) : response.usage().toUsage();
        return ChatCompletionResponse.singleMessage(
                id, Instant.now().getEpochSecond(), model, text.toString(), finishReason, usage);
    }

    /**
     * 把 Anthropic 的 stop_reason 映射为 OpenAI 的 finish_reason。
     *
     * @param stopReason Anthropic 结束原因
     * @return OpenAI 结束原因
     */
    private String mapStopReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "refusal" -> "content_filter"; // Claude 4.x 拒答，映射到 OpenAI 闭集枚举
            default -> stopReason;
        };
    }

    /** 错误响应体截断到 500 字符，避免日志与异常信息爆炸。 */
    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
}
