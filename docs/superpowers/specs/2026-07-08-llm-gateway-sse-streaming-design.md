# llm-gateway SSE 流式支持 设计文档

日期:2026-07-08
状态:已获用户批准的设计,待实施
范围:C:/practice/llm-gateway(后端)+ C:/practice/llm-gateway-ui(前端 Playground)
前置:安全基线子项目已完成(admin JWT、API Key 哈希、审计)

## 1. 背景与目标

`ChatCompletionRequest.stream` 字段目前被忽略:客户端传 `stream=true` 仍收到非流式 JSON,违反 OpenAI 协议。本子项目让网关成为**真正的流式代理**:上游逐 token 产出、网关逐帧转发、下游打字机呈现,同时保持流水线(鉴权/限流/配额/护栏/缓存/路由/容错/计费/审计)在流式下语义完整。

**已确认的 4 项决策(用户 2026-07-08 批准):**
1. 并发模型:**虚拟线程 + 阻塞直写**(`spring.threads.virtual.enabled=true`,不引入 WebFlux/SseEmitter 线程池)
2. 流式出站护栏:**增量检查 + 命中即截断**(每 chunk 后对累计文本跑敏感词,命中→error 事件+断流)
3. 缓存:**完整参与**(命中→回放成流;未命中→流完组装完整响应写缓存)
4. 前端:**包含 Playground 页**(管理端验证工具)

## 2. 对外协议行为(OpenAI 兼容)

### 2.1 非流式(不变)
`stream` 缺省/false:现有 JSON 响应,零改动。控制器在进入非流式路径前把 `stream`/`stream_options` 置 null(防止客户端乱传 `stream=false + stream_options` 被上游拒绝)。

### 2.2 流式响应格式
`stream=true` 时:

```
HTTP/1.1 200
Content-Type: text/event-stream;charset=UTF-8
Cache-Control: no-cache
X-Accel-Buffering: no

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1751900000,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1751900000,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1751900000,"model":"deepseek-v4-pro","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","created":1751900000,"model":"deepseek-v4-pro","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}

data: [DONE]

```

- 每帧 `data: {json}\n\n`,逐帧 flush
- usage chunk(`choices:[]` + `usage`)**仅当客户端传 `stream_options.include_usage=true`** 时发送,位于 [DONE] 前 —— OpenAI 语义
- 网关对上游**始终**强制 `stream_options.include_usage=true`(计费需要),客户端没要则该 chunk 被网关消费不转发

### 2.3 错误语义
| 时机 | 行为 |
|---|---|
| 首帧写出前(鉴权/限流/配额/入站护栏/路由链全败/上游首包失败) | 普通 JSON 错误 + 原状态码(现有 GlobalExceptionHandler,零改动) |
| 首帧写出后上游/网关异常 | `data: {"error":{"message":"...","type":"gateway_error","code":"PROVIDER_ERROR"}}\n\n` 后关闭连接(不发 [DONE]) |
| 增量护栏命中 | 同上,code=`content_filtered`,message 说明被截断 |
| 客户端断开 | 网关立即中止上游读取并释放连接,落审计 |

## 3. 组件设计

### 3.1 新增 DTO(`api/dto/`)
```java
// StreamOptions.java — OpenAI stream_options
public record StreamOptions(@JsonProperty("include_usage") Boolean includeUsage) {}

// ChatCompletionRequest 增加字段(withModel 副本方法同步携带):
@JsonProperty("stream_options") StreamOptions streamOptions

// ChatCompletionChunk.java — 流式帧,object 固定 "chat.completion.chunk"
public record ChatCompletionChunk(String id, String object, long created, String model,
                                  List<DeltaChoice> choices, Usage usage) {
    public static ChatCompletionChunk first(String id, long created, String model);     // delta={role:"assistant",content:""}
    public static ChatCompletionChunk content(String id, long created, String model, String text); // delta={content:text}
    public static ChatCompletionChunk finish(String id, long created, String model, String finishReason); // delta={}, finish_reason
    public static ChatCompletionChunk usageOnly(String id, long created, String model, Usage usage);      // choices=[]
    public String deltaContent(); // 便捷取 choices[0].delta.content,无则 ""
}

// DeltaChoice.java — delta 复用 ChatMessage(全局 non_null 序列化,null 字段自动省略)
public record DeltaChoice(int index, ChatMessage delta, @JsonProperty("finish_reason") String finishReason) {}
```
注意:`delta:{}` 空对象需要 `new ChatMessage(null, null)`(序列化为 `{}`,而非省略 delta 键)。

### 3.2 SSE 解析(`provider/sse/SseEventReader.java`)
纯工具类,从 `InputStream` 逐事件读取:
- 按 SSE 规范分帧:空行结束一个事件;`data:` 行累积(多行以 `\n` join);`event:` 行记录事件名;`:` 开头注释行忽略;容忍 `\r\n`
- API:迭代器风格 —— 构造 `new SseEventReader(InputStream)`,`next()` 返回 `SseEvent(String event, String data)` record,流结束返回 null;实现 `Closeable`
- UTF-8 解码;**不负责** JSON 解析与 [DONE] 判断(调用方职责)

### 3.3 供应商流式接口(`provider/LlmProvider.java`)
```java
/**
 * 流式对话补全:把上游产出翻译成 OpenAI chunk 逐个回调,阻塞至流结束。
 * @return 上游给出的用量;上游未提供则返回 null(由调用方估算兜底)
 * @throws ProviderException 上游失败;onChunk 抛出的非受检异常原样上抛(中止即关闭上游连接)
 */
default Usage chatStream(ChatCompletionRequest request, Consumer<ChatCompletionChunk> onChunk) {
    ChatCompletionResponse full = chat(request);   // 不支持原生流式的供应商:非流式结果一次性回放
    String id = full.id(); long created = full.created();
    onChunk.accept(ChatCompletionChunk.first(id, created, full.model()));
    onChunk.accept(ChatCompletionChunk.content(id, created, full.model(), full.firstContent()));
    onChunk.accept(ChatCompletionChunk.finish(id, created, full.model(),
            full.choices().isEmpty() ? "stop" : full.choices().get(0).finishReason()));
    return full.usage();
}
```

**OpenAiCompatibleProvider.chatStream** 覆写:
- 上游请求体 = 原请求副本,强制 `stream=true` + `stream_options={"include_usage":true}`
- `restClient.post()...exchange((req, resp) -> ...)`:非 2xx → 读 body 抛 `ProviderException`;2xx → `SseEventReader` 逐帧:`[DONE]` 结束;usage chunk(choices 空且 usage 非空)→ 记下 usage **不回调**;其余 → 反序列化为 `ChatCompletionChunk` 回调
- exchange 回调返回后 RestClient 自动关连接;onChunk 抛异常 → 异常穿出 exchange,连接同样被关闭(中止上游)

**AnthropicProvider.chatStream** 覆写(协议翻译):
- 请求体 = `toAnthropicBody(request)` + `"stream": true`
- 事件映射:`message_start`(取 message.id + usage.input_tokens)→ 发 `first` chunk;`content_block_delta`(delta.type=text_delta)→ 发 `content` chunk;`message_delta`(取 delta.stop_reason 经 mapStopReason + usage.output_tokens)→ 记下;`message_stop` → 发 `finish` chunk 并返回 `Usage.of(input, output)`;`ping` 忽略;`error` 事件 → ProviderException

**MockProvider.chatStream** 覆写:model 含 `fail` 抛异常(演示流式降级);否则把 mock 回复文本切成 3 段依次回调(first→3×content→finish),返回估算 usage。

### 3.4 流式写出(`core/streaming/SseWriter.java`)
封装对 `HttpServletResponse` 的 SSE 写出,**懒提交**:
- `write(chunk)`:首次调用时才设置响应头并提交(保证首帧前的失败仍能走 JSON 错误路径);序列化 chunk → `data: ...\n\n` → flush
- `started()`:是否已写出首帧(容错降级的分界)
- `writeUsage(chunk)` / `writeError(code, message)` / `done()`(写 `data: [DONE]\n\n`)
- 所有 `IOException` 包装为 `ClientDisconnectedException extends RuntimeException` 抛出(客户端断开的统一信号)
- 记录首帧时刻(供 TTFT)

### 3.5 流式聚合(`core/streaming/StreamAggregator.java`)
- `accept(chunk)`:累计 `deltaContent()` 到 StringBuilder;记录 id/model/finishReason
- 增量护栏:每次 accept 后调 `guardrailEngine.checkOutputText(累计文本)`,命中 → 抛 `GuardrailException`(GuardrailEngine 新增公开方法 `checkOutputText(String)`,内部与 `checkOutput(response)` 共用出站链;敏感词是子串匹配,对累计文本重复检查幂等且开销极小)
- `text()`:当前累计文本(截断/断开时估算用量、写审计用)
- `buildResponse(Usage)`:流完组装完整 `ChatCompletionResponse`(id/model/finishReason/全文/usage)供缓存与 finish() 复用

### 3.6 流式容错(`resilience/ResilientExecutor.java` 新增方法)
```java
/**
 * 流式执行:首帧写出前,失败照常「重试 + 熔断 + 换目标」;首帧写出后不可再降级,异常直接上抛。
 * ClientDisconnectedException 与 GuardrailException 不算供应商故障:不计熔断、不重试、直接上抛。
 */
public Usage executeStream(RouteDecision decision, StreamInvoker invoker, BooleanSupplier streamStarted)
// StreamInvoker.java:@FunctionalInterface Usage invokeStream(ProviderTarget target) throws Exception;
```
实现要点:复用现有循环骨架;每次捕获异常先判断——`ClientDisconnectedException`/`GuardrailException` 原样 rethrow(不 `breaker.onFailure()`);`streamStarted.getAsBoolean()==true` → `breaker.onFailure()` 后 rethrow(包装为 ProviderException 语义);否则按现有退避重试/换目标。

### 3.7 核心编排(`core/GatewayService.completeStream()`)
```java
public void completeStream(ChatCompletionRequest request, Principal principal, HttpServletResponse response)
```
流程(与 complete() 平行,共享前置检查与 finish()):
1. 授权、限流、配额、入站护栏、metrics.incRequest —— 与 complete() 完全一致
2. 缓存命中 → `SseWriter` 回放(first→content(全文)→finish→[按需 usage]→[DONE])→ `finish(context, cached, true)` → return
3. 路由 → `RouteDecision`
4. 构造 `SseWriter`、`StreamAggregator`;`Consumer<ChatCompletionChunk> onChunk = chunk -> { aggregator.accept(chunk)(内含增量护栏); writer.write(chunk); }`;usage chunk 由 provider 消费不进此回调
5. `Usage usage = resilientExecutor.executeStream(decision, target -> providerRegistry.get(target.provider()).chatStream(request.withModel(target.model()), onChunk), writer::started)`
6. usage 为 null → `Usage.of(TokenEstimator.estimate(request.messages()), TokenEstimator.estimate(aggregator.text()))` 兜底
7. `ChatCompletionResponse assembled = aggregator.buildResponse(usage)` → `cacheService.store(request, assembled)`
8. 客户端要 usage(`request.streamOptions().includeUsage()==true`)→ `writer.writeUsage(...)`;`writer.done()`
9. `finish(context, assembled, false)`(计费/指标/落库/配额,复用现有方法)

异常收尾(catch 分支,顺序即优先级):
1. `ClientDisconnectedException` → 不再写响应;落 request_log:status=`client_aborted`、usage=估算(TokenEstimator:请求消息 + aggregator.text());log.info
2. `GatewayException`(含 GuardrailException):
   - `!writer.started()` → 现有 persistError + 原样上抛(GlobalExceptionHandler → JSON 错误,含首个 chunk 即命中护栏的情形)
   - `writer.started()` → `writer.writeError(e.code(), e.getMessage())`(护栏命中即 code=`content_filtered`);GuardrailException 落 status=`guardrail_truncated` + 估算 usage,其余落 persistError(status=`error`);metrics.incError
3. 其它 `RuntimeException` → 按 2 的 started/!started 同款处理(包装为 ProviderException 语义)

`finish()`/`persistError` 保持签名不变;新增 status 取值 `client_aborted`/`guardrail_truncated` 由 completeStream 的专用收尾写入(提取私有方法 `persistPartial(context, status, errorCode, usage)`)。

### 3.8 控制器(`api/ChatCompletionController.java`)
```java
@PostMapping("/v1/chat/completions")
public ChatCompletionResponse chatCompletions(@Valid @RequestBody ChatCompletionRequest request,
                                              HttpServletRequest http, HttpServletResponse response) {
    Principal principal = ...; // 不变
    if (Boolean.TRUE.equals(request.stream())) {
        gatewayService.completeStream(request, principal, response);
        return null; // SSE 已直写并提交
    }
    return gatewayService.complete(stripStreamHints(request), principal); // stream/streamOptions 置 null
}
```

### 3.9 观测(`observability/MetricsRecorder.java`、`core/GatewayContext.java`)
- MetricsRecorder 新增:`incStreamRequest()`(counter `llm.gateway.stream.requests`)、`recordTtft(long millis)`(timer `llm.gateway.ttft`)
- GatewayContext 新增:`markStreamed()`、`setFirstTokenMillis(long)`,`toLogLine` 追加 `stream=true ttftMs=N`(非流式不追加)
- completeStream 在首帧写出后记录 TTFT(SseWriter 暴露首帧时刻)

### 3.10 配置(`application.yaml`)
```yaml
spring:
  threads:
    virtual:
      enabled: true   # 流式长连接占用请求线程,虚拟线程使其不消耗平台线程池
```
上游读超时沿用 `gateway.http.read-timeout-ms=30000`:SimpleClientHttpRequestFactory 的 readTimeout 是 socket 级 per-read,流式下语义即「相邻两帧最大间隔 30s」,合理,不加新配置。

## 4. 前端 Playground(llm-gateway-ui)

- 路由 `/playground`,meta `{title:'试运行', subtitle:'验证网关流式接口', icon:'ChatDotRound'}`;main.js 注册 ChatDotRound 图标
- `src/views/Playground.vue`:
  - 顶部配置条:API Key 输入(type=password,**仅存组件内存,明确不落 localStorage**)、模型输入(el-input,placeholder 提示常用模型名)、「清空对话」
  - 对话区:消息气泡列表(user/assistant),assistant 内容随流增量渲染;底部输入框 + 发送/停止按钮
  - 状态条:最近一轮 TTFT / 总耗时 / usage(prompt+completion tokens)
  - 实现:原生 `fetch('/v1/chat/completions', {stream:true, stream_options:{include_usage:true}})`(axios 不支持流式);`AbortController` 支持停止与组件卸载中止;`resp.ok=false` → 解析 JSON error 展示(401→「Key 无效或未授权」);`resp.body.getReader()` + `TextDecoder` 按 `\n\n` 分帧,解析 `data:`,处理 [DONE]/usage chunk/error 事件
  - **不经过** `src/api/http.js`(那是 admin JWT 通道;/v1 用租户 API Key)
- `vite.config.js`:proxy 增加 `'/v1'` → `http://localhost:8080`(与 /admin 同款)
- `src/views/Logs.vue`:status tag 映射补 `client_aborted`(warning)、`guardrail_truncated`(danger)

## 5. 测试策略

后端单测:
- `SseEventReaderTest`:单事件/多事件分帧、多行 data、CRLF、注释行、event 行、流中断
- `ChatCompletionChunkTest`:工厂方法序列化形状(delta 空对象、usage chunk choices 为空数组、non_null 省略)
- `LlmProviderDefaultStreamTest`:default chatStream 用 chat() 回放的 chunk 序列与返回 usage
- `MockProviderStreamTest`:分片序列、fail 模型抛异常
- `AnthropicStreamTranslationTest`:事件翻译(input/output tokens、stop_reason 映射)——将翻译逻辑提取为可测纯函数
- `StreamAggregatorTest`:累计、buildResponse、护栏命中抛 GuardrailException
- `ResilientExecutorStreamTest`:首帧前失败换目标成功;首帧后失败直接上抛;ClientDisconnected/Guardrail 不计熔断
- `GatewayServiceStreamTest` / MockMvc 集成:stream=true 走 mock → 断言 Content-Type、chunk 序列、[DONE]、include_usage 行为、缓存命中回放、request_log 状态

冒烟(实施完成后):`curl -N --noproxy '*'` 观察逐帧输出;Playground 手测打字机效果、停止按钮、错误 Key 提示。

## 6. 边界(本期不做)

- 多 choice(n>1)流式、`tool_calls`/function 增量(当前 DTO 无 tools)
- 精确 token 计数(子项目 3:上游 usage 缺失时目前用 TokenEstimator 估算)
- SSE 心跳保活注释帧(上游 30s 读超时已兜底断死流)
- 语义缓存命中判定逻辑不变(流式只是回放形态)
- Actuator/运维硬化(子项目 5)

## 7. 验收标准

1. `stream=true` 经 mock/真实供应商返回合法 OpenAI chunk 流,`curl -N` 可见逐帧输出,终帧 [DONE]
2. `stream=false` 行为与改造前完全一致;全部既有测试通过
3. 流开始前的鉴权/限流/配额/护栏错误仍为 JSON + 正确状态码
4. 敏感词在流中命中 → 流被截断且收到 error 事件;request_log 出现 `guardrail_truncated`
5. 缓存:同请求第二次 stream=true 命中回放(request_log status=`cache_hit`);流式产生的响应可被后续非流式请求命中
6. include_usage=true 时收到 usage chunk;request_log 的 token/cost 与上游 usage 一致
7. Playground 页可用打字机效果对话,停止按钮生效
