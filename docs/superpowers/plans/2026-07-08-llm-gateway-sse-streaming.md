# llm-gateway SSE 流式支持 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让网关按 OpenAI 协议真正支持 `stream=true`(SSE 逐帧转发),流水线(护栏/缓存/容错/计费/审计)在流式下语义完整,并提供管理端 Playground 验证页。

**Architecture:** MVC + 虚拟线程阻塞直写(不引入 WebFlux,兼容 SCA 终态);`SseWriter` 懒提交响应头保证首帧前错误仍走 JSON;`LlmProvider.chatStream` 默认实现自动降级为非流式回放;容错「首帧前可重试换目标,首帧后只能断流」;增量敏感词截断;流完组装完整响应复用缓存与 finish() 计费。

**Tech Stack:** Spring Boot 4.1 / Framework 7、RestClient `.exchange()` 流式读、Jackson 3(tools.jackson,注解用 com.fasterxml)、JUnit 5 + MockMvc、Vue 3 + fetch/ReadableStream。

**Spec:** `docs/superpowers/specs/2026-07-08-llm-gateway-sse-streaming-design.md`

**环境注意(两项目均非 git 仓库,不执行 git 命令,验证靠编译+测试):**
- 后端构建:`cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test`(mvnw 坏,用系统 Maven)
- 集成测试依赖本地 MySQL(root/123456,库 llm_gateway,已跑过 schema.sql/seed.sql)
- 前端:`cd C:/practice/llm-gateway-ui && npm run build`
- curl 本机必须 `--noproxy '*'`

---

## 文件总览

| 动作 | 路径 | 职责 |
|---|---|---|
| 新建 | `api/dto/StreamOptions.java` | stream_options DTO |
| 新建 | `api/dto/ChatCompletionChunk.java` | OpenAI 流式帧 DTO + 工厂 |
| 新建 | `api/dto/DeltaChoice.java` | chunk 内 choice(delta) |
| 修改 | `api/dto/ChatCompletionRequest.java` | +streamOptions 字段与 3 个副本方法 |
| 修改 | `api/dto/ChatMessage.java` | +@JsonInclude(NON_NULL) |
| 新建 | `provider/sse/SseEventReader.java` | SSE 分帧解析(纯工具) |
| 新建 | `exception/ClientDisconnectedException.java` | 客户端断开信号 |
| 新建 | `core/streaming/SseWriter.java` | SSE 写出(懒提交) |
| 新建 | `core/streaming/StreamAggregator.java` | 聚合 + 增量护栏 |
| 修改 | `guardrail/GuardrailEngine.java` | +checkOutputText(String) |
| 修改 | `provider/LlmProvider.java` | +default chatStream |
| 修改 | `provider/MockProvider.java` | 覆写 chatStream(分片模拟) |
| 修改 | `provider/OpenAiCompatibleProvider.java` | 覆写 chatStream(exchange 流读) |
| 修改 | `provider/AnthropicProvider.java` | 覆写 chatStream(事件翻译) |
| 新建 | `provider/AnthropicStreamEvent.java` | Anthropic 流事件 DTO |
| 新建 | `resilience/StreamInvoker.java` | 流式调用回调接口 |
| 修改 | `resilience/ResilientExecutor.java` | +executeStream |
| 修改 | `core/GatewayService.java` | +completeStream 及收尾 |
| 修改 | `core/GatewayContext.java` | +stream/ttft 字段 |
| 修改 | `observability/MetricsRecorder.java` | +incStreamRequest/recordTtft |
| 修改 | `api/ChatCompletionController.java` | stream 分支 |
| 修改 | `resources/application.yaml` | 虚拟线程开关 |
| 新建(测试) | `SseEventReaderTest` `ChatCompletionChunkTest` `MockProviderStreamTest` `StreamAggregatorTest` `SseWriterTest` `ResilientExecutorStreamTest` `OpenAiStreamReadTest` `AnthropicStreamReadTest` `ChatCompletionStreamIntegrationTest` | 见各任务 |
| 修改(前端) | `src/router/index.js` `src/main.js` `src/views/Logs.vue` | 路由/图标/状态 tag |
| 新建(前端) | `src/views/Playground.vue` | 试运行页 |

---

### Task 1: 流式 DTO(StreamOptions / ChatCompletionChunk / DeltaChoice / Request 扩展)

**Files:**
- Create: `src/main/java/com/llm/gateway/api/dto/StreamOptions.java`
- Create: `src/main/java/com/llm/gateway/api/dto/DeltaChoice.java`
- Create: `src/main/java/com/llm/gateway/api/dto/ChatCompletionChunk.java`
- Modify: `src/main/java/com/llm/gateway/api/dto/ChatCompletionRequest.java`
- Modify: `src/main/java/com/llm/gateway/api/dto/ChatMessage.java`
- Test: `src/test/java/com/llm/gateway/api/dto/ChatCompletionChunkTest.java`

- [x] **Step 1.1: 写失败测试**(序列化形状是协议兼容的命门,先钉死)

```java
package com.llm.gateway.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class ChatCompletionChunkTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void contentChunkSerializesDeltaContentOnly() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.content("c1", 100, "m1", "你好"));
        assertTrue(json.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(json.contains("\"delta\":{\"content\":\"你好\"}"));
        assertFalse(json.contains("usage"), "内容帧不应含 usage");
        assertFalse(json.contains("finish_reason"), "NON_NULL 下 null finish_reason 应省略");
    }

    @Test
    void finishChunkSerializesEmptyDelta() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.finish("c1", 100, "m1", "stop"));
        assertTrue(json.contains("\"delta\":{}"));
        assertTrue(json.contains("\"finish_reason\":\"stop\""));
    }

    @Test
    void usageChunkHasEmptyChoices() {
        String json = mapper.writeValueAsString(
                ChatCompletionChunk.usageOnly("c1", 100, "m1", Usage.of(3, 5)));
        assertTrue(json.contains("\"choices\":[]"));
        assertTrue(json.contains("\"total_tokens\":8"));
    }

    @Test
    void deltaContentExtractsTextSafely() {
        assertEquals("hi", ChatCompletionChunk.content("c", 1, "m", "hi").deltaContent());
        assertEquals("", ChatCompletionChunk.finish("c", 1, "m", "stop").deltaContent());
        assertEquals("", ChatCompletionChunk.usageOnly("c", 1, "m", Usage.of(1, 1)).deltaContent());
    }

    @Test
    void openAiChunkJsonDeserializes() {
        String json = "{\"id\":\"x\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\","
                + "\"system_fingerprint\":\"fp\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"A\"},"
                + "\"logprobs\":null,\"finish_reason\":null}]}";
        ChatCompletionChunk chunk = mapper.readValue(json, ChatCompletionChunk.class);
        assertEquals("A", chunk.deltaContent()); // 未知字段(system_fingerprint/logprobs)被容忍
    }
}
```

- [x] **Step 1.2: 运行确认失败**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test -Dtest=ChatCompletionChunkTest`
Expected: 编译失败(ChatCompletionChunk 不存在)

- [x] **Step 1.3: 实现 DTO**

`StreamOptions.java`:
```java
package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 协议的 {@code stream_options}(仅 stream=true 时有意义)。
 *
 * @param includeUsage 为 true 时,在 [DONE] 前额外发送一个含 usage 的 chunk
 */
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {
}
```

`DeltaChoice.java`:
```java
package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 流式帧里的一个候选(OpenAI 协议):与 {@link Choice} 的区别是增量放在 {@code delta} 而非 message。
 *
 * @param index        序号
 * @param delta        本帧增量(role 仅首帧出现,content 逐帧追加;结束帧为空对象)
 * @param finishReason 结束原因,仅结束帧非空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeltaChoice(int index, ChatMessage delta, @JsonProperty("finish_reason") String finishReason) {
}
```

`ChatCompletionChunk.java`:
```java
package com.llm.gateway.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 流式响应帧(OpenAI 协议),object 固定为 {@code chat.completion.chunk}。
 *
 * <p>标注 NON_NULL 使序列化形状不依赖全局配置:内容帧省略 usage,usage 帧的 choices 为空数组。
 *
 * @param id      响应 ID(同一次生成的所有帧相同)
 * @param object  固定 {@code chat.completion.chunk}
 * @param created 创建时间(epoch 秒)
 * @param model   物理模型名
 * @param choices 增量候选;usage 帧为空数组
 * @param usage   仅 usage 帧非空
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        List<DeltaChoice> choices,
        Usage usage) {

    private static final String OBJECT = "chat.completion.chunk";

    /** 首帧:delta 含 role=assistant 与空 content。 */
    public static ChatCompletionChunk first(String id, long created, String model) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage("assistant", ""), null)), null);
    }

    /** 内容帧:delta 仅含本帧增量文本。 */
    public static ChatCompletionChunk content(String id, long created, String model, String text) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage(null, text), null)), null);
    }

    /** 结束帧:delta 为空对象,携带 finish_reason。 */
    public static ChatCompletionChunk finish(String id, long created, String model, String finishReason) {
        return new ChatCompletionChunk(id, OBJECT, created, model,
                List.of(new DeltaChoice(0, new ChatMessage(null, null), finishReason)), null);
    }

    /** usage 帧:choices 为空数组,仅在客户端要求 include_usage 时发送。 */
    public static ChatCompletionChunk usageOnly(String id, long created, String model, Usage usage) {
        return new ChatCompletionChunk(id, OBJECT, created, model, List.of(), usage);
    }

    /** @return 第一个候选的增量文本,无则空串 */
    public String deltaContent() {
        if (choices == null || choices.isEmpty()) {
            return "";
        }
        ChatMessage delta = choices.get(0).delta();
        return delta == null || delta.content() == null ? "" : delta.content();
    }
}
```

`ChatMessage.java` 在 record 声明上加注解(import `com.fasterxml.jackson.annotation.JsonInclude`):
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content) {
```
(其余不动;作用是让 `delta:{}` 与 `delta:{"content":"x"}` 的形状确定,普通消息 role/content 始终非空,无行为变化)

`ChatCompletionRequest.java` 整体替换 record 头与方法(javadoc 同步):
```java
/**
 * ……(原 javadoc 保留,补充:)
 * @param stream        是否流式(true 走 SSE)
 * @param streamOptions OpenAI stream_options,仅流式时有意义
 */
public record ChatCompletionRequest(
        @NotBlank(message = "model 不能为空") String model,
        @NotEmpty(message = "messages 不能为空") List<ChatMessage> messages,
        Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("max_tokens") Integer maxTokens,
        Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions) {

    /** 路由后把 model 替换为物理模型的副本。 */
    public ChatCompletionRequest withModel(String resolvedModel) {
        return new ChatCompletionRequest(resolvedModel, messages, temperature, topP, maxTokens, stream, streamOptions);
    }

    /** 供应商上游流式调用副本:强制 stream=true 且 include_usage=true(网关计费需要)。 */
    public ChatCompletionRequest forStreamingUpstream() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, true, new StreamOptions(true));
    }

    /** 非流式路径副本:清掉 stream 与 stream_options,避免上游拒绝「stream=false + stream_options」。 */
    public ChatCompletionRequest withoutStreamHints() {
        return new ChatCompletionRequest(model, messages, temperature, topP, maxTokens, null, null);
    }

    /** @return 客户端是否要求 usage 帧 */
    public boolean wantsUsageChunk() {
        return streamOptions != null && Boolean.TRUE.equals(streamOptions.includeUsage());
    }
}
```

- [x] **Step 1.4: 修复既有 6 参构造调用点**

Run: `grep -rn "new ChatCompletionRequest(" src/main src/test --include=*.java`
对每个 6 参调用点在末尾追加 `, null`(第 7 参 streamOptions)。预计出现在测试与 Fixtures 相关文件中。

- [x] **Step 1.5: 全量编译 + 本任务测试通过**

Run: `mvn -q test -Dtest=ChatCompletionChunkTest`
Expected: PASS(5/5)

Run: `mvn -q test-compile`
Expected: BUILD SUCCESS(所有调用点已修)

---

### Task 2: SseEventReader(SSE 分帧解析)

**Files:**
- Create: `src/main/java/com/llm/gateway/provider/sse/SseEventReader.java`
- Test: `src/test/java/com/llm/gateway/provider/sse/SseEventReaderTest.java`

- [x] **Step 2.1: 写失败测试**

```java
package com.llm.gateway.provider.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class SseEventReaderTest {

    private SseEventReader readerOf(String text) {
        return new SseEventReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesSequentialDataEvents() throws IOException {
        try (SseEventReader r = readerOf("data: one\n\ndata: two\n\n")) {
            assertEquals("one", r.next().data());
            assertEquals("two", r.next().data());
            assertNull(r.next());
        }
    }

    @Test
    void toleratesCrLfAndNoSpaceAfterColon() throws IOException {
        try (SseEventReader r = readerOf("data:[DONE]\r\n\r\n")) {
            assertEquals("[DONE]", r.next().data());
        }
    }

    @Test
    void joinsMultiLineDataWithNewline() throws IOException {
        try (SseEventReader r = readerOf("data: a\ndata: b\n\n")) {
            assertEquals("a\nb", r.next().data());
        }
    }

    @Test
    void capturesEventNameAndSkipsComments() throws IOException {
        try (SseEventReader r = readerOf(": keep-alive\nevent: message_start\ndata: {}\n\n")) {
            SseEventReader.SseEvent ev = r.next();
            assertEquals("message_start", ev.event());
            assertEquals("{}", ev.data());
        }
    }

    @Test
    void returnsTrailingEventWithoutFinalBlankLine() throws IOException {
        try (SseEventReader r = readerOf("data: tail")) {
            assertEquals("tail", r.next().data()); // 流意外截断也要吐出半个事件
            assertNull(r.next());
        }
    }
}
```

- [x] **Step 2.2: 运行确认失败**

Run: `mvn -q test -Dtest=SseEventReaderTest`
Expected: 编译失败(类不存在)

- [x] **Step 2.3: 实现**

```java
package com.llm.gateway.provider.sse;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE(text/event-stream)分帧读取器:把字节流切成一个个事件,供各供应商适配器消费。
 *
 * <p>只负责按规范分帧(空行结束事件、data 多行以 \n 拼接、event 行记名、冒号注释行忽略、
 * 容忍 CRLF 与冒号后无空格),不做 JSON 解析、不认识 [DONE]——那是调用方的协议语义。
 */
public final class SseEventReader implements Closeable {

    /** 一个 SSE 事件:event 行的名字(可空)与拼接后的 data。 */
    public record SseEvent(String event, String data) {
    }

    private final BufferedReader reader;

    /** @param in 上游响应体字节流(UTF-8) */
    public SseEventReader(InputStream in) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    /**
     * 读取下一个事件;流结束返回 null。流在事件中途截断时,把已积累的半个事件吐出。
     *
     * @return 事件,流尽为 null
     * @throws IOException 读取失败(含 socket 读超时)
     */
    public SseEvent next() throws IOException {
        String event = null;
        StringBuilder data = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data != null) {
                    return new SseEvent(event, data.toString());
                }
                event = null; // 连续空行:重置后继续
                continue;
            }
            if (line.startsWith(":")) {
                continue; // 注释/心跳行
            }
            if (line.startsWith("data:")) {
                String value = fieldValue(line, 5);
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            } else if (line.startsWith("event:")) {
                event = fieldValue(line, 6);
            }
            // 其余字段(id:/retry:)网关不需要,忽略
        }
        return data == null ? null : new SseEvent(event, data.toString());
    }

    /** 取字段值:冒号后至多一个空格按规范剥掉。 */
    private static String fieldValue(String line, int prefixLength) {
        String value = line.substring(prefixLength);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
```

- [x] **Step 2.4: 测试通过**

Run: `mvn -q test -Dtest=SseEventReaderTest`
Expected: PASS(5/5)

---

### Task 3: LlmProvider.chatStream 默认实现 + MockProvider 分片覆写

**Files:**
- Modify: `src/main/java/com/llm/gateway/provider/LlmProvider.java`
- Modify: `src/main/java/com/llm/gateway/provider/MockProvider.java`
- Test: `src/test/java/com/llm/gateway/provider/MockProviderStreamTest.java`

- [x] **Step 3.1: 写失败测试**

```java
package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ProviderException;

class MockProviderStreamTest {

    private final MockProvider provider = new MockProvider();

    private ChatCompletionRequest request(String model) {
        return new ChatCompletionRequest(model, List.of(ChatMessage.user("你好")), null, null, null, true, null);
    }

    @Test
    void streamsFirstThenContentPiecesThenFinish() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = provider.chatStream(request("mock-small"), chunks::add);

        assertNotNull(usage);
        assertTrue(chunks.size() >= 3, "至少 first + 1 content + finish");
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role());
        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason());
        String full = chunks.stream().map(ChatCompletionChunk::deltaContent).reduce("", String::concat);
        assertTrue(full.contains("[mock:mock-small] 收到：你好"), "分片拼回完整文本,实际: " + full);
    }

    @Test
    void failModelThrows() {
        assertThrows(ProviderException.class, () -> provider.chatStream(request("mock-fail"), c -> { }));
    }

    @Test
    void dirtyModelEmitsSensitiveWordForGuardrailDemo() {
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        provider.chatStream(request("mock-dirty"), chunks::add);
        String full = chunks.stream().map(ChatCompletionChunk::deltaContent).reduce("", String::concat);
        assertTrue(full.contains("制造炸弹"), "dirty 模型输出需含演示敏感词");
    }

    @Test
    void defaultChatStreamReplaysNonStreamingResult() {
        // 匿名实现只提供 chat(),验证接口 default 方法的回放降级
        LlmProvider nonStreaming = new LlmProvider() {
            @Override public String name() { return "plain"; }
            @Override public com.llm.gateway.api.dto.ChatCompletionResponse chat(ChatCompletionRequest r) {
                return com.llm.gateway.api.dto.ChatCompletionResponse.singleMessage(
                        "id-1", 42, r.model(), "整段回复", "stop", Usage.of(2, 3));
            }
        };
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = nonStreaming.chatStream(request("any"), chunks::add);

        assertEquals(3, chunks.size()); // first + content(全文) + finish
        assertEquals("整段回复", chunks.get(1).deltaContent());
        assertEquals(Usage.of(2, 3), usage);
    }
}
```

- [x] **Step 3.2: 运行确认失败**

Run: `mvn -q test -Dtest=MockProviderStreamTest`
Expected: 编译失败(chatStream 不存在)

- [x] **Step 3.3: LlmProvider 增加 default 方法**

在接口中追加(import `java.util.function.Consumer`、`com.llm.gateway.api.dto.ChatCompletionChunk`、`com.llm.gateway.api.dto.Usage`):
```java
    /**
     * 流式对话补全:把上游产出翻译成 OpenAI chunk 逐个回调,阻塞至流结束。
     *
     * <p>默认实现供不支持原生流式的供应商自动降级:调用 {@link #chat} 后把完整结果按
     * 「首帧 → 全文内容帧 → 结束帧」一次性回放。onChunk 抛出的非受检异常会原样上抛,
     * 调用方以此中止流(实现方应随之释放上游连接)。
     *
     * @param request 已解析到物理模型的请求
     * @param onChunk 每个内容帧的消费者(usage 帧不经过它,由返回值交付)
     * @return 上游给出的用量;上游未提供时为 null(由调用方估算兜底)
     * @throws com.llm.gateway.exception.ProviderException 调用失败(可被上层重试/降级)
     */
    default Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        ChatCompletionResponse full = chat(request);
        String finishReason = full.choices() == null || full.choices().isEmpty()
                ? "stop" : full.choices().get(0).finishReason();
        onChunk.accept(ChatCompletionChunk.first(full.id(), full.created(), full.model()));
        onChunk.accept(ChatCompletionChunk.content(full.id(), full.created(), full.model(), full.firstContent()));
        onChunk.accept(ChatCompletionChunk.finish(full.id(), full.created(), full.model(), finishReason));
        return full.usage();
    }
```

- [x] **Step 3.4: MockProvider 覆写 chatStream**

在 MockProvider 中追加(import `java.util.function.Consumer`、`ChatCompletionChunk`):
```java
    /** 演示用敏感词回复:模型名含 dirty 时输出含敏感词的文本,用于验证流式护栏截断。 */
    private static final String DIRTY_DEMO_REPLY = "本回复用于演示流式护栏截断，接下来出现敏感词：制造炸弹，其后的内容不应到达客户端。";

    @Override
    public Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        if (request.model() != null && request.model().contains("fail")) {
            throw new ProviderException("Mock 供应商模拟失败：" + request.model());
        }
        String content = request.model() != null && request.model().contains("dirty")
                ? DIRTY_DEMO_REPLY
                : "[mock:" + request.model() + "] 收到：" + lastUserContent(request.messages());
        String id = "chatcmpl-mock-" + UUID.randomUUID();
        long created = Instant.now().getEpochSecond();
        onChunk.accept(ChatCompletionChunk.first(id, created, request.model()));
        for (String piece : splitIntoPieces(content)) {
            onChunk.accept(ChatCompletionChunk.content(id, created, request.model(), piece));
        }
        onChunk.accept(ChatCompletionChunk.finish(id, created, request.model(), "stop"));
        return Usage.of(TokenEstimator.estimate(request.messages()), TokenEstimator.estimate(content));
    }

    /**
     * 把文本大致均分为 3 片(不足 3 字符时整段一片),模拟流式分帧。
     *
     * @param content 完整文本
     * @return 非空分片列表
     */
    private static List<String> splitIntoPieces(String content) {
        if (content.length() < 3) {
            return List.of(content);
        }
        int third = content.length() / 3;
        return List.of(
                content.substring(0, third),
                content.substring(third, 2 * third),
                content.substring(2 * third));
    }
```

- [x] **Step 3.5: 测试通过**

Run: `mvn -q test -Dtest=MockProviderStreamTest`
Expected: PASS(4/4)

---

### Task 4: GuardrailEngine.checkOutputText + StreamAggregator

**Files:**
- Modify: `src/main/java/com/llm/gateway/guardrail/GuardrailEngine.java`
- Create: `src/main/java/com/llm/gateway/core/streaming/StreamAggregator.java`
- Test: `src/test/java/com/llm/gateway/core/streaming/StreamAggregatorTest.java`

- [x] **Step 4.1: 写失败测试**

```java
package com.llm.gateway.core.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.guardrail.PromptInjectionGuardrail;
import com.llm.gateway.guardrail.SensitiveWordGuardrail;
import com.llm.gateway.Fixtures;

class StreamAggregatorTest {

    private GuardrailEngine engine() {
        // Fixtures 配置的敏感词表为 ["制造炸弹"]
        return new GuardrailEngine(new SensitiveWordGuardrail(Fixtures.properties()),
                new PromptInjectionGuardrail());
    }

    @Test
    void accumulatesDeltasAndBuildsFullResponse() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        aggregator.accept(ChatCompletionChunk.first("id-9", 77, "m-x"));
        aggregator.accept(ChatCompletionChunk.content("id-9", 77, "m-x", "你"));
        aggregator.accept(ChatCompletionChunk.content("id-9", 77, "m-x", "好"));
        aggregator.accept(ChatCompletionChunk.finish("id-9", 77, "m-x", "length"));

        assertEquals("你好", aggregator.text());
        ChatCompletionResponse response = aggregator.buildResponse(Usage.of(1, 2));
        assertEquals("id-9", response.id());
        assertEquals(77, response.created());
        assertEquals("m-x", response.model());
        assertEquals("你好", response.firstContent());
        assertEquals("length", response.choices().get(0).finishReason());
        assertEquals(3, response.usage().totalTokens());
    }

    @Test
    void sensitiveWordAcrossChunksTriggersGuardrail() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        aggregator.accept(ChatCompletionChunk.content("id", 1, "m", "教我制造"));
        // 敏感词跨帧拼出:「制造」+「炸弹」→ 累计文本命中
        assertThrows(GuardrailException.class,
                () -> aggregator.accept(ChatCompletionChunk.content("id", 1, "m", "炸弹的方法")));
        assertEquals("教我制造炸弹的方法", aggregator.text(), "命中帧的文本已累计(用于审计用量估算)");
    }

    @Test
    void buildResponseFallsBackWhenNoChunksArrived() {
        StreamAggregator aggregator = new StreamAggregator(engine());
        ChatCompletionResponse response = aggregator.buildResponse(Usage.of(0, 0));
        assertEquals("", response.firstContent());
        assertEquals("stop", response.choices().get(0).finishReason());
    }
}
```

注意:`SensitiveWordGuardrail`/`PromptInjectionGuardrail` 的构造签名以现有代码为准(实现前先读这两个类;若构造不是 `(GatewayProperties)`/无参,按实际调整测试的 engine() 工厂)。

- [x] **Step 4.2: 运行确认失败**

Run: `mvn -q test -Dtest=StreamAggregatorTest`
Expected: 编译失败

- [x] **Step 4.3: GuardrailEngine 提取 checkOutputText**

把现有 `checkOutput` 改为委托,新增公开方法(javadoc 同步):
```java
    /**
     * 出站检查:取模型回复文本后逐一过护栏。
     *
     * @param response 响应
     * @throws GuardrailException 命中任一出站护栏
     */
    public void checkOutput(ChatCompletionResponse response) {
        checkOutputText(response.firstContent());
    }

    /**
     * 出站检查(文本版):流式场景对「累计文本」增量调用。
     * 敏感词表小且为子串匹配,重复全量检查开销可忽略。
     *
     * @param text 已累计的输出文本
     * @throws GuardrailException 命中任一出站护栏
     */
    public void checkOutputText(String text) {
        runChain(outputGuardrails, text, "出站");
    }
```

- [x] **Step 4.4: 实现 StreamAggregator**

```java
package com.llm.gateway.core.streaming;

import java.time.Instant;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.guardrail.GuardrailEngine;

/**
 * 流式聚合器:边转发边累计增量文本,并对累计文本做增量出站护栏检查;
 * 流结束后把碎片组装回完整 {@link ChatCompletionResponse},复用非流式的缓存与计费收尾。
 *
 * <p>护栏命中时在「写出该帧之前」抛出 {@link com.llm.gateway.exception.GuardrailException},
 * 保证命中帧不会到达客户端(此前的帧已送达,是流式固有限制)。
 */
public class StreamAggregator {

    private final GuardrailEngine guardrailEngine;
    private final StringBuilder text = new StringBuilder();

    private String id;
    private String model;
    private long created;
    private String finishReason;

    /** @param guardrailEngine 护栏引擎(出站链) */
    public StreamAggregator(GuardrailEngine guardrailEngine) {
        this.guardrailEngine = guardrailEngine;
    }

    /**
     * 吸收一帧:记录元数据、累计文本、跑增量护栏。
     *
     * @param chunk 供应商翻译后的 OpenAI 帧
     * @throws com.llm.gateway.exception.GuardrailException 累计文本命中出站护栏
     */
    public void accept(ChatCompletionChunk chunk) {
        if (chunk.id() != null) {
            id = chunk.id();
        }
        if (chunk.model() != null) {
            model = chunk.model();
        }
        if (chunk.created() > 0) {
            created = chunk.created();
        }
        if (chunk.choices() != null && !chunk.choices().isEmpty()
                && chunk.choices().get(0).finishReason() != null) {
            finishReason = chunk.choices().get(0).finishReason();
        }
        String delta = chunk.deltaContent();
        if (!delta.isEmpty()) {
            text.append(delta);
            guardrailEngine.checkOutputText(text.toString());
        }
    }

    /** @return 当前累计文本(截断/断开时用于估算已产生用量) */
    public String text() {
        return text.toString();
    }

    /** @return 实际产出的模型名,尚无帧时为 null */
    public String model() {
        return model;
    }

    /**
     * 流结束后组装完整响应(供缓存与 finish() 计费落库)。
     *
     * @param usage 最终用量(上游给出或估算)
     * @return 完整响应
     */
    public ChatCompletionResponse buildResponse(Usage usage) {
        return ChatCompletionResponse.singleMessage(
                id == null ? "chatcmpl-stream" : id,
                created > 0 ? created : Instant.now().getEpochSecond(),
                model,
                text.toString(),
                finishReason == null ? "stop" : finishReason,
                usage);
    }
}
```

- [x] **Step 4.5: 测试通过 + 既有护栏测试回归**

Run: `mvn -q test -Dtest='StreamAggregatorTest,GuardrailEngineTest'`
Expected: 全 PASS

---

### Task 5: SseWriter + ClientDisconnectedException

**Files:**
- Create: `src/main/java/com/llm/gateway/exception/ClientDisconnectedException.java`
- Create: `src/main/java/com/llm/gateway/core/streaming/SseWriter.java`
- Test: `src/test/java/com/llm/gateway/core/streaming/SseWriterTest.java`

- [x] **Step 5.1: 写失败测试**(用 spring-test 的 MockHttpServletResponse)

```java
package com.llm.gateway.core.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UnsupportedEncodingException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;

import tools.jackson.databind.ObjectMapper;

class SseWriterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void lazyCommitsHeadersOnFirstFrame() throws UnsupportedEncodingException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        assertFalse(writer.started(), "未写帧前不应提交响应头");

        writer.write(ChatCompletionChunk.content("c1", 1, "m", "hi"));

        assertTrue(writer.started());
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertTrue(response.getContentAsString().startsWith("data: {"));
        assertTrue(response.getContentAsString().endsWith("\n\n"));
    }

    @Test
    void writesUsageErrorAndDoneFrames() throws UnsupportedEncodingException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        writer.write(ChatCompletionChunk.usageOnly("c1", 1, "m", Usage.of(1, 2)));
        writer.writeError("content_filtered", "出站内容被拦截");
        writer.done();

        String body = response.getContentAsString();
        assertTrue(body.contains("\"total_tokens\":3"));
        assertTrue(body.contains("\"error\":"));
        assertTrue(body.contains("\"code\":\"content_filtered\""));
        assertTrue(body.contains("\"type\":\"gateway_error\""));
        assertTrue(body.endsWith("data: [DONE]\n\n"));
    }

    @Test
    void recordsFirstFrameNanos() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SseWriter writer = new SseWriter(response, mapper);
        assertEquals(0, writer.firstFrameNanos());
        writer.write(ChatCompletionChunk.content("c", 1, "m", "x"));
        assertTrue(writer.firstFrameNanos() > 0);
    }
}
```

- [x] **Step 5.2: 运行确认失败**

Run: `mvn -q test -Dtest=SseWriterTest`
Expected: 编译失败

- [x] **Step 5.3: 实现异常与写出器**

`ClientDisconnectedException.java`:
```java
package com.llm.gateway.exception;

/**
 * 客户端在流式响应期间断开连接。
 *
 * <p>不继承 {@link GatewayException}:对端已消失,不存在「给客户端的错误响应」语义;
 * 上层据此中止上游读取、尽力落审计,且<strong>不计入</strong>供应商熔断统计。
 */
public class ClientDisconnectedException extends RuntimeException {

    public ClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`SseWriter.java`:
```java
package com.llm.gateway.core.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.exception.ClientDisconnectedException;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * SSE 帧写出器:把 chunk 序列化成 {@code data: ...\n\n} 帧并逐帧 flush。
 *
 * <p><strong>懒提交</strong>是关键设计:响应头在第一帧写出时才设置,因此首帧之前的任何失败
 * 仍可走 {@code GlobalExceptionHandler} 返回带状态码的 JSON 错误——与 OpenAI 语义一致。
 * 写出失败(IOException)统一转成 {@link ClientDisconnectedException} 作为客户端断开信号。
 */
public class SseWriter {

    private final HttpServletResponse response;
    private final ObjectMapper objectMapper;

    private OutputStream out;
    private boolean started;
    private long firstFrameNanos;

    /**
     * @param response     Servlet 响应(阻塞直写,虚拟线程上运行)
     * @param objectMapper Spring 配置好的 Jackson 3 ObjectMapper
     */
    public SseWriter(HttpServletResponse response, ObjectMapper objectMapper) {
        this.response = response;
        this.objectMapper = objectMapper;
    }

    /** @return 是否已写出首帧(容错「可否换目标重试」与错误形态「JSON 还是 error 帧」的分界) */
    public boolean started() {
        return started;
    }

    /** @return 首帧写出时刻的纳秒时间戳,未写出为 0(供 TTFT 统计) */
    public long firstFrameNanos() {
        return firstFrameNanos;
    }

    /**
     * 写出一帧 chunk。
     *
     * @param chunk 帧
     * @throws ClientDisconnectedException 客户端已断开
     */
    public void write(ChatCompletionChunk chunk) {
        writeFrame(objectMapper.writeValueAsString(chunk));
    }

    /**
     * 写出错误帧(仅用于首帧已发出后的中途失败)。
     *
     * @param code    机器可读错误码
     * @param message 人类可读信息
     */
    public void writeError(String code, String message) {
        writeFrame(objectMapper.writeValueAsString(Map.of("error", Map.of(
                "message", message == null ? "" : message,
                "type", "gateway_error",
                "code", code))));
    }

    /** 写出终帧 {@code data: [DONE]}。 */
    public void done() {
        writeFrame("[DONE]");
    }

    /** 首帧懒提交响应头,之后逐帧写出并 flush。 */
    private void writeFrame(String data) {
        try {
            if (!started) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/event-stream;charset=UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("X-Accel-Buffering", "no");
                out = response.getOutputStream();
                started = true;
                firstFrameNanos = System.nanoTime();
            }
            out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ClientDisconnectedException("客户端已断开：" + e.getMessage(), e);
        }
    }
}
```

- [x] **Step 5.4: 测试通过**

Run: `mvn -q test -Dtest=SseWriterTest`
Expected: PASS(3/3)

---

### Task 6: ResilientExecutor.executeStream + StreamInvoker

**Files:**
- Create: `src/main/java/com/llm/gateway/resilience/StreamInvoker.java`
- Modify: `src/main/java/com/llm/gateway/resilience/ResilientExecutor.java`
- Test: `src/test/java/com/llm/gateway/resilience/ResilientExecutorStreamTest.java`

- [x] **Step 6.1: 写失败测试**

```java
package com.llm.gateway.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ClientDisconnectedException;
import com.llm.gateway.exception.GuardrailException;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.ProviderTarget;
import com.llm.gateway.router.RouteDecision;

class ResilientExecutorStreamTest {

    private final CircuitBreakerRegistry registry =
            new CircuitBreakerRegistry(Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));
    private final ResilientExecutor executor =
            new ResilientExecutor(registry, Fixtures.properties(60, 300, 1_000_000L, 2, 30, 0));

    @Test
    void fallsBackToNextTargetBeforeFirstFrame() {
        RouteDecision decision = new RouteDecision(
                new ProviderTarget("s-fail", "m"), List.of(new ProviderTarget("s-ok", "m")));

        Usage usage = executor.executeStream(decision, target -> {
            if ("s-fail".equals(target.provider())) {
                throw new ProviderException("连接失败");
            }
            return Usage.of(1, 2);
        }, () -> false); // 首帧未发出

        assertEquals(3, usage.totalTokens());
    }

    @Test
    void rethrowsImmediatelyAfterFirstFrame() {
        RouteDecision decision = new RouteDecision(
                new ProviderTarget("mid-fail", "m"), List.of(new ProviderTarget("never", "m")));
        AtomicBoolean invokedFallback = new AtomicBoolean(false);

        assertThrows(ProviderException.class, () ->
                executor.executeStream(decision, target -> {
                    if ("never".equals(target.provider())) {
                        invokedFallback.set(true);
                    }
                    throw new ProviderException("流中途断");
                }, () -> true)); // 首帧已发出

        assertEquals(false, invokedFallback.get(), "首帧后不得再尝试降级目标");
    }

    @Test
    void clientDisconnectDoesNotTripBreakerNorRetry() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("cd", "m"), List.of());

        assertThrows(ClientDisconnectedException.class, () ->
                executor.executeStream(decision, target -> {
                    throw new ClientDisconnectedException("gone", null);
                }, () -> true));

        assertEquals(CircuitBreaker.State.CLOSED, registry.get("cd").state(),
                "客户端断开不是供应商故障,熔断计数不得增加");
    }

    @Test
    void guardrailAbortPropagatesWithoutBreakerPenalty() {
        RouteDecision decision = new RouteDecision(new ProviderTarget("gr", "m"), List.of());

        assertThrows(GuardrailException.class, () ->
                executor.executeStream(decision, target -> {
                    throw new GuardrailException("出站命中");
                }, () -> true));

        assertEquals(CircuitBreaker.State.CLOSED, registry.get("gr").state());
    }
}
```

- [x] **Step 6.2: 运行确认失败**

Run: `mvn -q test -Dtest=ResilientExecutorStreamTest`
Expected: 编译失败

- [x] **Step 6.3: 实现**

`StreamInvoker.java`:
```java
package com.llm.gateway.resilience;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.provider.ProviderTarget;

/**
 * 对单个目标发起一次<strong>流式</strong>调用的回调,与 {@link ProviderInvoker} 平行。
 * 实现内部逐帧回调写出,阻塞至流结束,返回上游用量(可为 null)。
 */
@FunctionalInterface
public interface StreamInvoker {

    /**
     * 流式调用指定目标。
     *
     * @param target 路由目标
     * @return 上游用量,未提供为 null
     * @throws Exception 调用失败(首帧前将触发重试/降级)
     */
    Usage invokeStream(ProviderTarget target) throws Exception;
}
```

`ResilientExecutor` 追加方法(import `java.util.function.BooleanSupplier`、`Usage`、`ClientDisconnectedException`、`GuardrailException`、`ProviderException`;javadoc 与类注释风格一致):
```java
    /**
     * 流式版容错执行:首帧写出前,失败照常「重试 + 熔断 + 换目标」;首帧写出后响应已不可回退,
     * 异常直接上抛由调用方断流。{@link ClientDisconnectedException}(客户端断开)与
     * {@link GuardrailException}(网关主动截断)不是供应商故障:不计熔断、不重试、原样上抛。
     *
     * @param decision      路由决策(首选 + 降级链)
     * @param invoker       针对单个目标的流式调用逻辑
     * @param streamStarted 查询「首帧是否已写给客户端」
     * @return 第一个成功目标返回的用量(可为 null)
     * @throws NoProviderAvailableException 首帧前所有目标都失败或被熔断
     */
    public Usage executeStream(RouteDecision decision, StreamInvoker invoker, BooleanSupplier streamStarted) {
        Exception lastError = null;
        for (ProviderTarget target : decision.chain()) {
            CircuitBreaker breaker = breakerRegistry.get(target.provider());
            if (!breaker.allowRequest()) {
                log.warn("目标 {} 的熔断器已打开，跳过", target);
                continue;
            }
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    Usage usage = invoker.invokeStream(target);
                    breaker.onSuccess();
                    if (attempt > 0) {
                        log.info("目标 {} 第 {} 次重试成功", target, attempt);
                    }
                    return usage;
                } catch (ClientDisconnectedException | GuardrailException e) {
                    throw e; // 非供应商故障:不计熔断、不重试
                } catch (Exception e) {
                    lastError = e;
                    breaker.onFailure();
                    if (streamStarted.getAsBoolean()) {
                        // 首帧已写给客户端:无法换目标重放,只能断流
                        throw e instanceof ProviderException pe ? pe
                                : new ProviderException("流式输出已开始后上游失败：" + e.getMessage(), e);
                    }
                    log.warn("目标 {} 流式调用失败（第 {} 次尝试）：{}", target, attempt + 1, e.getMessage());
                    if (attempt < maxRetries && !backoff(attempt)) {
                        throw new NoProviderAvailableException("重试等待被中断", e);
                    }
                }
            }
        }
        throw new NoProviderAvailableException("路由链上所有目标均不可用：" + decision.chain(), lastError);
    }
```

- [x] **Step 6.4: 测试通过 + 既有容错测试回归**

Run: `mvn -q test -Dtest='ResilientExecutorStreamTest,ResilientExecutorTest'`
Expected: 全 PASS

---

### Task 7: OpenAiCompatibleProvider.chatStream(exchange 流式读)

**Files:**
- Modify: `src/main/java/com/llm/gateway/provider/OpenAiCompatibleProvider.java`
- Test: `src/test/java/com/llm/gateway/provider/OpenAiStreamReadTest.java`

设计:HTTP 交互不做单测(集成/冒烟覆盖),把「SSE 事件流 → chunk 回调 + usage 提取」提炼成包私有方法 `readStream`,用 ByteArrayInputStream 直接测。

- [x] **Step 7.1: 写失败测试**

```java
package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

class OpenAiStreamReadTest {

    private final OpenAiCompatibleProvider provider =
            new OpenAiCompatibleProvider("openai", "http://localhost:1", "test-key", new ObjectMapper(), null);

    private Usage read(String sse, List<ChatCompletionChunk> sink) throws IOException {
        try (SseEventReader reader = new SseEventReader(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))) {
            return provider.readStream(reader, sink::add);
        }
    }

    @Test
    void forwardsContentChunksConsumesUsageAndStopsAtDone() throws IOException {
        String sse = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

                data: [DONE]

                data: {"id":"never","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[]}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals(3, chunks.size(), "usage 帧被网关消费,不进回调;[DONE] 后的数据不读");
        assertEquals("Hi", chunks.get(1).deltaContent());
        assertEquals("stop", chunks.get(2).choices().get(0).finishReason());
        assertEquals(9, usage.totalTokens());
    }

    @Test
    void returnsNullUsageWhenUpstreamOmitsIt() throws IOException {
        String sse = """
                data: {"id":"c2","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"X"},"finish_reason":null}]}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        assertNull(read(sse, chunks));
        assertEquals(1, chunks.size());
    }
}
```

- [x] **Step 7.2: 运行确认失败**

Run: `mvn -q test -Dtest=OpenAiStreamReadTest`
Expected: 编译失败(readStream 不存在)

- [x] **Step 7.3: 实现 chatStream + readStream**

在 OpenAiCompatibleProvider 追加(import `java.io.IOException`、`java.nio.charset.StandardCharsets`、`java.util.function.Consumer`、`ChatCompletionChunk`、`Usage`、`ClientDisconnectedException`、`GuardrailException`、`SseEventReader`):
```java
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
                            String error = new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new ProviderException(name + " 流式调用失败 HTTP "
                                    + clientResponse.getStatusCode() + "：" + truncate(error));
                        }
                        try (SseEventReader reader = new SseEventReader(clientResponse.getBody())) {
                            return readStream(reader, onChunk);
                        }
                    });
        } catch (ProviderException | ClientDisconnectedException | GuardrailException e) {
            throw e; // 断流信号与已分类错误原样上抛(exchange 回调抛出的非受检异常会穿透到这里)
        } catch (Exception e) {
            throw new ProviderException("调用 " + name + " 流式失败：" + e.getMessage(), e);
        }
    }

    /**
     * 消费上游 SSE 事件流:内容帧回调转发;usage 帧(choices 空且带 usage)被网关吃掉用于计费;
     * 读到 [DONE] 停止。
     *
     * @param reader  SSE 分帧读取器
     * @param onChunk 内容帧消费者
     * @return 上游给出的用量,未给为 null
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
            if ((chunk.choices() == null || chunk.choices().isEmpty()) && chunk.usage() != null) {
                usage = chunk.usage();
                continue;
            }
            onChunk.accept(chunk);
        }
        return usage;
    }

    /** 错误响应体截断到 500 字符,避免日志与异常信息爆炸。 */
    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500) + "…";
    }
```

- [x] **Step 7.4: 测试通过**

Run: `mvn -q test -Dtest=OpenAiStreamReadTest`
Expected: PASS(2/2)

---

### Task 8: AnthropicProvider.chatStream(事件翻译)

**Files:**
- Create: `src/main/java/com/llm/gateway/provider/AnthropicStreamEvent.java`
- Modify: `src/main/java/com/llm/gateway/provider/AnthropicProvider.java`
- Test: `src/test/java/com/llm/gateway/provider/AnthropicStreamReadTest.java`

- [x] **Step 8.1: 写失败测试**

```java
package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionChunk;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.ProviderException;
import com.llm.gateway.provider.sse.SseEventReader;

import tools.jackson.databind.ObjectMapper;

class AnthropicStreamReadTest {

    private final AnthropicProvider provider =
            new AnthropicProvider(Fixtures.properties(), new ObjectMapper());

    private Usage read(String sse, List<ChatCompletionChunk> sink) throws IOException {
        try (SseEventReader reader = new SseEventReader(
                new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)))) {
            return provider.readAnthropicStream(reader, "claude-opus-4-8", sink::add);
        }
    }

    @Test
    void translatesAnthropicEventsToOpenAiChunks() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_01","role":"assistant","usage":{"input_tokens":25,"output_tokens":1}}}

                event: ping
                data: {"type":"ping"}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"！"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals(4, chunks.size()); // first + 2 content + finish
        assertEquals("msg_01", chunks.get(0).id());
        assertEquals("assistant", chunks.get(0).choices().get(0).delta().role());
        assertEquals("你好", chunks.get(1).deltaContent());
        assertEquals("！", chunks.get(2).deltaContent());
        assertEquals("stop", chunks.get(3).choices().get(0).finishReason(), "end_turn 映射为 stop");
        assertEquals("claude-opus-4-8", chunks.get(1).model());
        assertEquals(Usage.of(25, 12), usage);
    }

    @Test
    void errorEventThrowsProviderException() {
        String sse = """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;
        assertThrows(ProviderException.class, () -> read(sse, new ArrayList<>()));
    }

    @Test
    void truncatedStreamStillEmitsFinishAndReturnsKnownUsage() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_02","usage":{"input_tokens":10,"output_tokens":1}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"半"}}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);

        assertEquals("stop", chunks.get(chunks.size() - 1).choices().get(0).finishReason(),
                "流截断时补发结束帧,保证客户端拿到合法收尾");
        assertEquals(10, usage.promptTokens());
    }
}
```

- [x] **Step 8.2: 运行确认失败**

Run: `mvn -q test -Dtest=AnthropicStreamReadTest`
Expected: 编译失败

- [x] **Step 8.3: 实现事件 DTO 与翻译**

`AnthropicStreamEvent.java`(包私有):
```java
package com.llm.gateway.provider;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Anthropic Messages 流式事件(仅取网关需要的字段,未知字段忽略)。
 * 事件类型见 type:message_start / content_block_delta / message_delta / message_stop / ping / error。
 *
 * @param type    事件类型
 * @param message message_start 携带的消息头(id 与 input_tokens)
 * @param delta   content_block_delta 的文本增量,或 message_delta 的 stop_reason
 * @param usage   message_delta 携带的累计输出用量
 */
record AnthropicStreamEvent(
        String type,
        StartMessage message,
        Delta delta,
        AnthropicResponse.AnthropicUsage usage) {

    /** message_start 的 message 字段。 */
    record StartMessage(String id, AnthropicResponse.AnthropicUsage usage) {
    }

    /** 双用途 delta:text_delta 时有 type/text,message_delta 时有 stop_reason。 */
    record Delta(String type, String text, @JsonProperty("stop_reason") String stopReason) {
    }
}
```

AnthropicProvider 追加(import `java.io.IOException`、`java.nio.charset.StandardCharsets`、`java.util.function.Consumer`、`ChatCompletionChunk`、`Usage`、`ClientDisconnectedException`、`GuardrailException`、`SseEventReader`、`org.springframework.http.HttpHeaders` 不需要——沿用 x-api-key):
```java
    @Override
    public Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderException("Anthropic api-key 未配置（设置 ANTHROPIC_API_KEY 环境变量）");
        }
        try {
            Map<String, Object> body = toAnthropicBody(request);
            body.put("stream", true);
            String requestBody = objectMapper.writeValueAsString(body);
            return restClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(requestBody)
                    .exchange((clientRequest, clientResponse) -> {
                        if (clientResponse.getStatusCode().isError()) {
                            String error = new String(clientResponse.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new ProviderException("Anthropic 流式调用失败 HTTP "
                                    + clientResponse.getStatusCode() + "：" + error);
                        }
                        try (SseEventReader reader = new SseEventReader(clientResponse.getBody())) {
                            return readAnthropicStream(reader, request.model(), onChunk);
                        }
                    });
        } catch (ProviderException | ClientDisconnectedException | GuardrailException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderException("调用 Anthropic 流式失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把 Anthropic 事件流翻译成 OpenAI chunk:message_start→首帧(取 id 与 input_tokens),
     * content_block_delta→内容帧,message_delta→记录 stop_reason 与 output_tokens,
     * message_stop→结束帧;ping/content_block_start/content_block_stop 忽略;error→ProviderException。
     * 流意外截断时补发结束帧并返回已知用量。
     *
     * @param reader  SSE 分帧读取器
     * @param model   请求的物理模型名(chunk 的 model 字段)
     * @param onChunk 内容帧消费者
     * @return 用量(input 来自 message_start,output 来自最后一个 message_delta)
     * @throws IOException 读上游失败
     */
    Usage readAnthropicStream(SseEventReader reader, String model, Consumer<ChatCompletionChunk> onChunk)
            throws IOException {
        String id = "chatcmpl-anthropic";
        long created = Instant.now().getEpochSecond();
        int inputTokens = 0;
        int outputTokens = 0;
        String finishReason = "stop";
        boolean firstSent = false;

        SseEventReader.SseEvent event;
        while ((event = reader.next()) != null) {
            AnthropicStreamEvent parsed = objectMapper.readValue(event.data(), AnthropicStreamEvent.class);
            switch (parsed.type() == null ? "" : parsed.type()) {
                case "message_start" -> {
                    if (parsed.message() != null) {
                        if (parsed.message().id() != null) {
                            id = parsed.message().id();
                        }
                        if (parsed.message().usage() != null) {
                            inputTokens = parsed.message().usage().inputTokens();
                        }
                    }
                    onChunk.accept(ChatCompletionChunk.first(id, created, model));
                    firstSent = true;
                }
                case "content_block_delta" -> {
                    if (parsed.delta() != null && "text_delta".equals(parsed.delta().type())
                            && parsed.delta().text() != null) {
                        onChunk.accept(ChatCompletionChunk.content(id, created, model, parsed.delta().text()));
                    }
                }
                case "message_delta" -> {
                    if (parsed.delta() != null && parsed.delta().stopReason() != null) {
                        finishReason = mapStopReason(parsed.delta().stopReason());
                    }
                    if (parsed.usage() != null) {
                        outputTokens = parsed.usage().outputTokens();
                    }
                }
                case "message_stop" -> {
                    onChunk.accept(ChatCompletionChunk.finish(id, created, model, finishReason));
                    return Usage.of(inputTokens, outputTokens);
                }
                case "error" -> throw new ProviderException("Anthropic 流式返回错误：" + event.data());
                default -> { /* ping / content_block_start / content_block_stop 忽略 */ }
            }
        }
        if (firstSent) {
            onChunk.accept(ChatCompletionChunk.finish(id, created, model, finishReason)); // 截断兜底
        }
        return Usage.of(inputTokens, outputTokens);
    }
```

- [x] **Step 8.4: 测试通过**

Run: `mvn -q test -Dtest=AnthropicStreamReadTest`
Expected: PASS(3/3)

---

### Task 9: GatewayService.completeStream + 控制器分支 + 观测扩展 + 虚拟线程

**Files:**
- Modify: `src/main/java/com/llm/gateway/core/GatewayContext.java`
- Modify: `src/main/java/com/llm/gateway/observability/MetricsRecorder.java`
- Modify: `src/main/java/com/llm/gateway/core/GatewayService.java`
- Modify: `src/main/java/com/llm/gateway/api/ChatCompletionController.java`
- Modify: `src/main/resources/application.yaml`

本任务无独立单测(GatewayService 现无单测先例,组合行为由 Task 10 集成测试覆盖),以编译 + 全量既有测试回归为门槛。

- [x] **Step 9.1: GatewayContext 增加流式字段**

在字段区追加,并加对应方法(风格与现有 getter/setter 一致):
```java
    private boolean streamed;
    private long firstTokenMillis = -1;
```
```java
    /** 标记本次请求走流式路径。 */
    public void markStreamed() {
        this.streamed = true;
    }

    public boolean streamed() {
        return streamed;
    }

    /** @param firstTokenMillis 首帧写出时距请求开始的毫秒数(TTFT) */
    public void setFirstTokenMillis(long firstTokenMillis) {
        this.firstTokenMillis = firstTokenMillis;
    }

    public long firstTokenMillis() {
        return firstTokenMillis;
    }
```
`toLogLine` 改为(仅流式追加 stream/ttft 字段):
```java
    public String toLogLine(long nowNanos) {
        String base = String.format(
                "reqId=%s tenant=%s requested=%s served=%s cacheHit=%s tokens=%d cost=$%.6f elapsedMs=%d",
                requestId, tenant, requestedModel, servedModel, cacheHit, totalTokens, cost,
                elapsedMillis(nowNanos));
        return streamed ? base + String.format(" stream=true ttftMs=%d", firstTokenMillis) : base;
    }
```

- [x] **Step 9.2: MetricsRecorder 增加流式指标**

```java
    /** 记录一次流式请求。 */
    public void incStreamRequest() {
        registry.counter("llm.gateway.stream.requests").increment();
    }

    /**
     * 记录首 Token 延迟(TTFT):请求开始到第一帧写出的耗时,是流式体验的核心指标。
     *
     * @param millis 毫秒数
     */
    public void recordTtft(long millis) {
        registry.timer("llm.gateway.ttft").record(Duration.ofMillis(millis));
    }
```

- [x] **Step 9.3: GatewayService 增加 completeStream**

构造器追加 `ObjectMapper objectMapper` 参数与同名字段(import `tools.jackson.databind.ObjectMapper`,javadoc 更新);新增 import:`java.util.function.Consumer`、`jakarta.servlet.http.HttpServletResponse`、`ChatCompletionChunk`、`ClientDisconnectedException`、`GuardrailException`、`SseWriter`、`StreamAggregator`、`TokenEstimator`、`RouteDecision`(已有)。追加方法:

```java
    /**
     * 处理一次<strong>流式</strong>对话补全:前置检查与非流式一致;命中缓存则把完整响应回放成 SSE;
     * 否则逐帧「聚合(含增量护栏)→ 写出」,流完组装完整响应复用缓存与 {@link #finish} 计费落库。
     * 响应头懒提交:首帧前的失败原样上抛,仍由全局异常处理器返回 JSON 错误。
     *
     * @param request         已通过 Bean 校验的统一请求(stream=true)
     * @param principal       鉴权得到的主体
     * @param servletResponse Servlet 响应(SSE 直写,运行在虚拟线程上)
     */
    public void completeStream(ChatCompletionRequest request, Principal principal,
                               HttpServletResponse servletResponse) {
        GatewayContext context =
                new GatewayContext(UUID.randomUUID().toString(), principal.tenant(), request.model(), System.nanoTime());
        context.markStreamed();
        SseWriter writer = new SseWriter(servletResponse, objectMapper);
        StreamAggregator aggregator = new StreamAggregator(guardrailEngine);
        try {
            apiKeyService.authorize(principal, request.model());
            rateLimiter.acquire(principal.tenant());
            quotaService.checkQuota(principal.tenant());
            guardrailEngine.checkInput(request);
            metrics.incRequest(principal.tenant(), request.model());
            metrics.incStreamRequest();

            Optional<ChatCompletionResponse> cached = cacheService.lookup(request);
            if (cached.isPresent()) {
                replay(writer, cached.get(), request.wantsUsageChunk());
                recordTtft(context, writer);
                finish(context, cached.get(), true);
                return;
            }

            RouteDecision decision = router.route(request);
            Consumer<ChatCompletionChunk> onChunk = chunk -> {
                aggregator.accept(chunk); // 含增量出站护栏,命中即抛 GuardrailException(该帧不写出)
                writer.write(chunk);
            };
            Usage usage = resilientExecutor.executeStream(decision, target ->
                            providerRegistry.get(target.provider())
                                    .chatStream(request.withModel(target.model()), onChunk),
                    writer::started);
            recordTtft(context, writer);

            if (usage == null) {
                usage = Usage.of(TokenEstimator.estimate(request.messages()),
                        TokenEstimator.estimate(aggregator.text()));
            }
            ChatCompletionResponse assembled = aggregator.buildResponse(usage);
            cacheService.store(request, assembled);
            if (request.wantsUsageChunk()) {
                writer.write(ChatCompletionChunk.usageOnly(
                        assembled.id(), assembled.created(), assembled.model(), usage));
            }
            writer.done();
            finish(context, assembled, false);
        } catch (ClientDisconnectedException e) {
            log.info("[gateway] 客户端中途断开 reqId={} tenant={}", context.requestId(), context.tenant());
            persistPartial(request, context, aggregator, "client_aborted", null);
        } catch (GuardrailException e) {
            metrics.incError(e.code());
            if (!writer.started()) {
                persistError(context, e);
                throw e; // 首帧未发出:走全局处理器返回 JSON
            }
            tryWriteError(writer, e.code(), e.getMessage());
            persistPartial(request, context, aggregator, "guardrail_truncated", e.code());
        } catch (GatewayException e) {
            metrics.incError(e.code());
            if (!writer.started()) {
                persistError(context, e);
                throw e;
            }
            tryWriteError(writer, e.code(), e.getMessage());
            persistPartial(request, context, aggregator, "error", e.code());
        } catch (RuntimeException e) {
            metrics.incError("internal");
            if (!writer.started()) {
                throw e; // 交给全局兜底
            }
            log.warn("[gateway] 流式请求内部错误 reqId={}：{}", context.requestId(), e.getMessage(), e);
            tryWriteError(writer, "internal_error", "网关内部错误");
            persistPartial(request, context, aggregator, "error", "internal");
        }
    }

    /** 缓存命中的 SSE 回放:首帧 → 全文内容帧 → 结束帧 → [按需] usage 帧 → [DONE]。 */
    private void replay(SseWriter writer, ChatCompletionResponse cached, boolean wantsUsage) {
        String id = cached.id();
        long created = cached.created();
        String model = cached.model();
        String finishReason = cached.choices() == null || cached.choices().isEmpty()
                ? "stop" : cached.choices().get(0).finishReason();
        writer.write(ChatCompletionChunk.first(id, created, model));
        writer.write(ChatCompletionChunk.content(id, created, model, cached.firstContent()));
        writer.write(ChatCompletionChunk.finish(id, created, model, finishReason));
        if (wantsUsage && cached.usage() != null) {
            writer.write(ChatCompletionChunk.usageOnly(id, created, model, cached.usage()));
        }
        writer.done();
    }

    /** 首帧已写出且尚未记录时,记录 TTFT(上下文 + 指标)。 */
    private void recordTtft(GatewayContext context, SseWriter writer) {
        if (writer.started() && context.firstTokenMillis() < 0) {
            long ttft = context.elapsedMillis(writer.firstFrameNanos()); // 首帧时刻 − 请求起始
            context.setFirstTokenMillis(ttft);
            metrics.recordTtft(ttft);
        }
    }

    /** 中途终止(断开/截断/流中失败)的落库:用估算用量尽力计费,状态区分终止原因。 */
    private void persistPartial(ChatCompletionRequest request, GatewayContext context,
                                StreamAggregator aggregator, String status, String errorCode) {
        try {
            int promptTokens = TokenEstimator.estimate(request.messages());
            int completionTokens = TokenEstimator.estimate(aggregator.text());
            Usage usage = Usage.of(promptTokens, completionTokens);
            String servedModel = aggregator.model();
            double cost = servedModel == null ? 0.0 : costCalculator.cost(servedModel, usage);
            long latencyMs = context.elapsedMillis(System.nanoTime());
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(), context.tenant(), context.requestedModel(), servedModel,
                    promptTokens, completionTokens, usage.totalTokens(), cost, false,
                    status, errorCode, latencyMs));
            quotaService.recordUsage(context.tenant(), usage.totalTokens());
        } catch (RuntimeException ex) {
            log.warn("写入流式中断审计记录时出错：{}", ex.getMessage());
        }
    }

    /** 尽力写出错误帧:客户端可能也已断开,失败仅忽略。 */
    private void tryWriteError(SseWriter writer, String code, String message) {
        try {
            writer.writeError(code, message);
        } catch (ClientDisconnectedException ignored) {
            // 对端已消失,无需处理
        }
    }
```

- [x] **Step 9.4: 控制器分支**

`ChatCompletionController.chatCompletions` 替换为(import `jakarta.servlet.http.HttpServletResponse`;javadoc 补一句流式说明):
```java
    @PostMapping("/v1/chat/completions")
    public ChatCompletionResponse chatCompletions(@Valid @RequestBody ChatCompletionRequest request,
                                                  HttpServletRequest http, HttpServletResponse response) {
        Principal principal = (Principal) http.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null) {
            // 正常情况下不会发生（过滤器已拦截），这里做防御性兜底
            throw new AuthenticationException("缺少鉴权主体");
        }
        if (Boolean.TRUE.equals(request.stream())) {
            gatewayService.completeStream(request, principal, response);
            return null; // SSE 已直写并提交;MVC 对 null 返回值不再写响应体
        }
        return gatewayService.complete(request.withoutStreamHints(), principal);
    }
```

- [x] **Step 9.5: application.yaml 开虚拟线程**

在 `spring:` 段(`application.name` 之后)插入:
```yaml
  # 流式响应会长时间占用请求线程(生成可达分钟级),虚拟线程使其不消耗平台线程池
  threads:
    virtual:
      enabled: true
```

- [x] **Step 9.6: 编译 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS,既有测试全绿(GatewayService 构造器新增参数由 Spring 自动注入,若有测试直接 new GatewayService 则按新签名补 ObjectMapper 参数)

---

### Task 10: 流式端到端集成测试(MockMvc,走 mock 供应商 + 本地 MySQL)

**Files:**
- Test: `src/test/java/com/llm/gateway/api/ChatCompletionStreamIntegrationTest.java`

- [x] **Step 10.1: 写集成测试**(先写全,首跑必失败——completeStream 已在 Task 9 完成,此处验证组合行为;若 Task 9 正确,可能直接通过,属正常)

```java
package com.llm.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.llm.gateway.auth.ApiKeyService;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatCompletionStreamIntegrationTest {

    private static final String TEST_KEY = "sk-it-stream-0000000000000001";
    private static final String TENANT = "it-stream";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiKeyService apiKeyService;

    @BeforeAll
    void insertTestKey() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models, enabled)
                VALUES (SHA2(?, 256), ?, ?, 'user', '*', 1)
                """, TEST_KEY, TEST_KEY.substring(0, 12), TENANT);
        apiKeyService.reload();
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_key WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
        apiKeyService.reload();
    }

    /** 每次用唯一 content 保证缓存不干扰(除专测缓存的用例)。 */
    private String body(String model, String content, String extra) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                + content + "\"}],\"stream\":true" + extra + "}";
    }

    private String postSse(String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(result.getResponse().getContentType().startsWith("text/event-stream"),
                "应为 SSE,实际: " + result.getResponse().getContentType());
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void streamEmitsChunksAndDoneWithoutUsageByDefault() throws Exception {
        String sse = postSse(body("mock-stream-it", "hi-" + UUID.randomUUID(), ""));
        assertTrue(sse.contains("chat.completion.chunk"));
        assertTrue(sse.contains("[mock:mock-stream-it]"));
        assertTrue(sse.trim().endsWith("data: [DONE]"));
        assertFalse(sse.contains("\"prompt_tokens\""), "未要求 include_usage 不应有 usage 帧");
    }

    @Test
    void includeUsageEmitsUsageChunkBeforeDone() throws Exception {
        String sse = postSse(body("mock-stream-it", "usage-" + UUID.randomUUID(),
                ",\"stream_options\":{\"include_usage\":true}"));
        int usagePos = sse.indexOf("\"prompt_tokens\"");
        int donePos = sse.indexOf("data: [DONE]");
        assertTrue(usagePos > 0, "应有 usage 帧");
        assertTrue(usagePos < donePos, "usage 帧应在 [DONE] 之前");
    }

    @Test
    void secondIdenticalRequestReplaysFromCache() throws Exception {
        String json = body("mock-stream-it", "cache-" + UUID.randomUUID(), "");
        postSse(json);
        postSse(json);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM request_log WHERE tenant = ? ORDER BY id DESC LIMIT 1",
                String.class, TENANT);
        assertEquals("cache_hit", status, "第二次同请求应命中缓存回放");
    }

    @Test
    void guardrailTruncatesMidStreamWithErrorFrame() throws Exception {
        // mock-dirty 模型输出含敏感词「制造炸弹」(入站消息干净,专测出站增量截断)
        String sse = postSse(body("mock-dirty-it", "clean-" + UUID.randomUUID(), ""));
        assertTrue(sse.contains("\"code\":\"content_filtered\""), "应写出截断错误帧");
        assertFalse(sse.contains("data: [DONE]"), "截断的流不应有 [DONE]");
        assertFalse(sse.contains("不应到达客户端"), "敏感词所在帧及之后内容不得写出");
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM request_log WHERE tenant = ? ORDER BY id DESC LIMIT 1",
                String.class, TENANT);
        assertEquals("guardrail_truncated", status);
    }

    @Test
    void preStreamErrorsStayJson() throws Exception {
        // 无 Authorization → 过滤器 401 JSON(流前错误不应变成 SSE)
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mock-stream-it", "x", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonStreamPathUnchanged() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"mock-plain-it\",\"messages\":[{\"role\":\"user\",\"content\":\"ns-"
                                + UUID.randomUUID() + "\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").exists());
    }
}
```

实现注意:
- `mock-dirty-it` 含 "dirty" → MockProvider 输出演示敏感词;含前缀 "mock" → 路由直达 mock 供应商,无降级干扰
- 断言 `sse.trim().endsWith("data: [DONE]")`:MockMvc 拿到的是完整缓冲内容,结尾是 `data: [DONE]\n\n`,trim 后成立
- 若 `guardrailTruncatesMidStreamWithErrorFrame` 因分片边界导致第一片已含整个敏感词,断言依然成立(截断更早而已)
- 敏感词来自 application.yaml 的 `gateway.guardrail.sensitive-words`(含「制造炸弹」),集成测试用真实配置

- [x] **Step 10.2: 运行**

Run: `mvn -q test -Dtest=ChatCompletionStreamIntegrationTest`
Expected: PASS(6/6)。任何失败按信息修 Task 9 的编排逻辑(常见:响应头未懒提交、usage 帧顺序、withoutStreamHints 忘调)

- [x] **Step 10.3: 全量回归**

Run: `mvn -q test`
Expected: 全绿(既有 39 + 本子项目新增)

---

### Task 11: 前端 Playground 页 + Logs 状态 tag

**Files:**(全部在 C:/practice/llm-gateway-ui)
- Create: `src/views/Playground.vue`
- Modify: `src/router/index.js`(追加路由)
- Modify: `src/main.js`(注册菜单图标 ChatDotRound)
- Modify: `src/views/Logs.vue`(statusMeta + 筛选下拉补两个状态)

- [x] **Step 11.1: 路由与图标**

`src/router/index.js` 在 `/audit` 路由对象之后追加:
```javascript
  {
    path: '/playground',
    name: 'playground',
    component: () => import('../views/Playground.vue'),
    meta: { title: '试运行', subtitle: '直连 /v1 验证网关与流式输出', icon: 'ChatDotRound' }
  }
```

`src/main.js` 两处同步加 `ChatDotRound`:
```javascript
import { Cpu, DataLine, Key, Share, Money, Tickets, Stamp, ChatDotRound } from '@element-plus/icons-vue'
```
```javascript
for (const icon of [Cpu, DataLine, Key, Share, Money, Tickets, Stamp, ChatDotRound]) {
```

- [x] **Step 11.2: Logs.vue 状态映射**

`statusMeta` 替换为:
```javascript
const statusMeta = {
  success: { type: 'success', label: '成功' },
  cache_hit: { type: 'primary', label: '缓存命中' },
  error: { type: 'danger', label: '失败' },
  client_aborted: { type: 'warning', label: '客户端断开' },
  guardrail_truncated: { type: 'danger', label: '护栏截断' }
}
```
状态筛选 `el-select` 里补两项(「失败」之后):
```html
          <el-option label="客户端断开" value="client_aborted" />
          <el-option label="护栏截断" value="guardrail_truncated" />
```

- [x] **Step 11.3: Playground.vue**(整文件)

```vue
<script setup>
import { nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion, VideoPause, Delete } from '@element-plus/icons-vue'

/**
 * 试运行:管理员直连 /v1/chat/completions 验证网关(含 SSE 流式)。
 * - API Key 只存组件内存,刷新即失,绝不写 localStorage
 * - axios 不支持流式读取,这里用原生 fetch + ReadableStream 解析 SSE
 */
const config = reactive({ apiKey: '', model: 'default' })
const input = ref('')
const messages = ref([]) // { role: 'user'|'assistant', content, error? }
const streaming = ref(false)
const stats = reactive({ ttftMs: null, elapsedMs: null, usage: null })
const listEl = ref(null)
let controller = null

function scrollToBottom() {
  nextTick(() => { if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight })
}

function clearChat() {
  messages.value = []
  stats.ttftMs = null; stats.elapsedMs = null; stats.usage = null
}

function stop() {
  if (controller) controller.abort()
}

async function send() {
  if (streaming.value) return
  if (!config.apiKey) { ElMessage.warning('请先填入 API Key(sk-gw-…)'); return }
  if (!config.model) { ElMessage.warning('请填入模型名或别名'); return }
  const text = input.value.trim()
  if (!text) return

  messages.value.push({ role: 'user', content: text })
  const history = messages.value
    .filter((m) => !m.error)
    .map((m) => ({ role: m.role, content: m.content }))
  const assistant = reactive({ role: 'assistant', content: '', error: false })
  messages.value.push(assistant)
  input.value = ''
  streaming.value = true
  stats.ttftMs = null; stats.elapsedMs = null; stats.usage = null
  controller = new AbortController()
  const startedAt = performance.now()
  scrollToBottom()

  try {
    const resp = await fetch('/v1/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${config.apiKey}` },
      body: JSON.stringify({
        model: config.model,
        messages: history,
        stream: true,
        stream_options: { include_usage: true }
      }),
      signal: controller.signal
    })

    if (!resp.ok) {
      // 流开始前的错误是普通 JSON(网关语义)
      let msg = `HTTP ${resp.status}`
      try {
        const err = await resp.json()
        msg = err?.error?.message || err?.message || err?.msg || msg
      } catch { /* 保留状态码信息 */ }
      if (resp.status === 401) msg = 'API Key 无效或未授权：' + msg
      assistant.content = msg
      assistant.error = true
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split('\n\n')
      buffer = frames.pop() // 半帧留到下一轮
      for (const frame of frames) {
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (payload === '[DONE]') continue
          let evt
          try { evt = JSON.parse(payload) } catch { continue }
          if (evt.error) {
            assistant.error = true
            assistant.content += `\n[已中断] ${evt.error.message || evt.error.code || '流被网关终止'}`
            continue
          }
          if (evt.usage) { stats.usage = evt.usage; continue }
          const delta = evt.choices?.[0]?.delta?.content
          if (delta) {
            if (stats.ttftMs === null) stats.ttftMs = Math.round(performance.now() - startedAt)
            assistant.content += delta
            scrollToBottom()
          }
        }
      }
    }
  } catch (e) {
    if (e.name === 'AbortError') {
      assistant.content += '\n[已停止]'
    } else {
      assistant.error = true
      assistant.content = '网络错误：' + (e.message || e)
    }
  } finally {
    stats.elapsedMs = Math.round(performance.now() - startedAt)
    streaming.value = false
    controller = null
    scrollToBottom()
  }
}

onBeforeUnmount(stop)
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">试运行</h2>
        <div class="page-subtitle">用租户 API Key 直连 /v1,验证路由、护栏与流式打字机效果</div>
      </div>
    </div>

    <div class="surface playground">
      <div class="toolbar">
        <el-input v-model="config.apiKey" type="password" show-password placeholder="API Key(sk-gw-…,仅存内存)"
                  style="width:280px" />
        <el-input v-model="config.model" placeholder="模型或别名:default / auto / cheap / mock-small"
                  style="width:260px" />
        <div class="spacer"></div>
        <el-button :disabled="streaming" @click="clearChat"><el-icon><Delete /></el-icon>&nbsp;清空对话</el-button>
      </div>

      <div ref="listEl" class="chat-list">
        <el-empty v-if="messages.length === 0" description="填好 Key 与模型,发一条消息试试" />
        <div v-for="(m, i) in messages" :key="i" class="bubble-row" :class="m.role">
          <div class="bubble" :class="{ error: m.error }">
            <pre>{{ m.content }}<span v-if="m.role === 'assistant' && streaming && i === messages.length - 1" class="cursor">▌</span></pre>
          </div>
        </div>
      </div>

      <div class="stats" v-if="stats.elapsedMs !== null || stats.ttftMs !== null">
        <el-tag v-if="stats.ttftMs !== null" type="info" effect="plain">首字 {{ stats.ttftMs }} ms</el-tag>
        <el-tag v-if="stats.elapsedMs !== null" type="info" effect="plain">总耗时 {{ stats.elapsedMs }} ms</el-tag>
        <el-tag v-if="stats.usage" type="info" effect="plain">
          Token {{ stats.usage.prompt_tokens }} 入 / {{ stats.usage.completion_tokens }} 出
        </el-tag>
      </div>

      <div class="composer">
        <el-input v-model="input" type="textarea" :rows="2" resize="none"
                  placeholder="输入消息,Ctrl+Enter 发送" @keydown.ctrl.enter.prevent="send" />
        <el-button v-if="!streaming" type="primary" :disabled="!input.trim()" @click="send">
          <el-icon><Promotion /></el-icon>&nbsp;发送
        </el-button>
        <el-button v-else type="warning" @click="stop">
          <el-icon><VideoPause /></el-icon>&nbsp;停止
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.playground { padding: 16px; display: flex; flex-direction: column; height: calc(100vh - 170px); }
.chat-list { flex: 1; overflow-y: auto; padding: 12px 4px; }
.bubble-row { display: flex; margin: 10px 0; }
.bubble-row.user { justify-content: flex-end; }
.bubble {
  max-width: 76%; padding: 10px 14px; border-radius: 12px;
  background: var(--el-fill-color-light); font-size: 14px; line-height: 1.6;
}
.bubble-row.user .bubble { background: var(--el-color-primary); color: #fff; }
.bubble.error { background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.bubble pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: inherit; }
.cursor { animation: blink 1s step-start infinite; }
@keyframes blink { 50% { opacity: 0; } }
.stats { display: flex; gap: 8px; padding: 8px 0; }
.composer { display: flex; gap: 12px; align-items: flex-end; padding-top: 8px; border-top: 1px solid var(--app-border); }
.composer .el-button { height: 54px; }
</style>
```

说明:`vite.config.js` 已有 `/v1` 代理,无需改动;Playground 不经过 `src/api/http.js`(那是 admin JWT 通道)。

- [x] **Step 11.4: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功,无 ESLint/编译错误

---

### Task 12: 全量回归 + 真机冒烟

**Files:** 无新增(验证任务)

- [x] **Step 12.1: 后端全量测试**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test`
Expected: 全绿

- [x] **Step 12.2: 起服务冒烟(SSE 逐帧)**

Run(后台起服务,等待就绪):
```bash
cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && \
GATEWAY_JWT_SECRET=smoke-secret-0123456789abcdef012345 mvn -q spring-boot:run
```
就绪后(另一会话):
```bash
c() { curl -s --noproxy '*' "$@"; }
# 1. 流式:mock 模型逐帧输出,含 [DONE]
c -N -X POST localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" \
  -d '{"model":"mock-smoke","messages":[{"role":"user","content":"流式冒烟"}],"stream":true,"stream_options":{"include_usage":true}}'
# 预期:多个 data: {...chat.completion.chunk...} 帧、一个 usage 帧、data: [DONE]
# 2. 同请求再发一次 → 回放(内容一帧到齐);查库确认 status=cache_hit
# 3. 护栏截断:model=mock-dirty-smoke → error 帧 content_filtered,无 [DONE]
# 4. 非流式回归:去掉 stream 字段 → 正常 JSON
# 5. 无 Key → 401 JSON
```
DB 抽查:
```bash
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 --default-character-set=utf8mb4 llm_gateway \
  -e "SELECT request_id,status,prompt_tokens,completion_tokens,latency_ms FROM request_log ORDER BY id DESC LIMIT 6;"
```
Expected: 依次可见 success(流式)/cache_hit/guardrail_truncated 等状态与非零 token
冒烟完成后停掉服务进程。

- [x] **Step 12.3: Playground 手测**(可选,如用户在场)

`npm run dev` → 登录 → 试运行页:粘贴 `sk-demo-tenant-a`、模型 `mock-ui`,验证打字机、停止按钮、TTFT/usage 显示、错误 Key 提示 401。

---

## 自审记录(writing-plans Self-Review)

1. **Spec 覆盖**:协议格式(Task 1/7)、SSE 解析(2)、默认降级+Mock(3)、增量护栏(4)、懒提交写出(5)、流式容错(6)、Anthropic 翻译(8)、编排+TTFT+虚拟线程+控制器(9)、验收标准 1–6(10/12)、Playground+Logs(11)=验收 7 ✔
2. **占位符扫描**:无 TBD/「稍后实现」;Task 4 对 Guardrail 构造签名的「以现有代码为准」是显式的实施期核对指令,附带了替代方案 ✔
3. **类型一致性**:chatStream 返回 Usage(3/6/7/8/9 一致);SseEventReader.next() → SseEvent(2/7/8);StreamAggregator.accept/text/model/buildResponse(4/9);SseWriter.write/writeError/done/started/firstFrameNanos(5/9);wantsUsageChunk/withoutStreamHints/forStreamingUpstream(1/7/9)✔






