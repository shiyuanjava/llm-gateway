# llm-gateway 精确 Token 计数与计费 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让网关计费与上游供应商口径一致：jtokkit 真实分词替换 4 字符估算、捕获缓存 token 并按缓存单价计费、定价缺失请求前 fail-close 拒绝（定价表尾部通配）。

**Architecture:** 单一口径 `Usage`（promptTokens 恒含缓存，两个缓存字段「只进不出」）；供应商差异全部收在适配器（Anthropic 做加法、OpenAI 拆明细）；`CostCalculator` 精确→最长前缀通配两级解析 + `requirePricing` fail-close；jtokkit 只做估算兜底，绝不覆盖上游 usage。

**Tech Stack:** Spring Boot 4.1、Jackson 3（tools.jackson，注解用 com.fasterxml）、jtokkit 1.1.0、MyBatis-Plus、JUnit 5 + MockMvc、Vue 3 + Element Plus。

**Spec:** `docs/superpowers/specs/2026-07-10-llm-gateway-token-counting-design.md`

**环境注意（两项目均非 git 仓库，不执行 git 命令，验证靠编译+测试）：**
- 后端构建：`cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test`（mvnw 坏，用系统 Maven）
- 集成测试依赖本地 MySQL（root/123456，库 llm_gateway）；本计划含两次 ALTER TABLE，必须在对应任务内先改库再跑测试
- 前端：`cd C:/practice/llm-gateway-ui && npm run build`
- curl 本机必须 `--noproxy '*'`
- MySQL CLI：`"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 llm_gateway`

---

## 文件总览

| 动作 | 路径 | 职责 |
|---|---|---|
| 修改 | `pom.xml` | +jtokkit 1.1.0 |
| 重写 | `provider/TokenEstimator.java` | jtokkit BPE 估算 + 模型感知编码选择 |
| 修改 | `api/dto/Usage.java` | +缓存字段（只进不出）+ OpenAI details 反序列化 |
| 修改 | `provider/AnthropicResponse.java` | AnthropicUsage +缓存字段 + toUsage() 加法归一化 |
| 修改 | `provider/AnthropicStreamEvent.java` | StreamUsage +缓存字段 |
| 修改 | `provider/AnthropicProvider.java` | 非流式/流式缓存捕获与加法 |
| 新建 | `exception/PricingNotConfiguredException.java` | fail-close 异常（422 pricing_not_configured） |
| 修改 | `persistence/repository/PricingRecord.java` | +缓存读/写单价（Double 可空） |
| 修改 | `persistence/entity/ModelPricingEntity.java` | +两列字段 |
| 修改 | `persistence/repository/impl/PricingRepositoryImpl.java` | 映射新字段 |
| 重写 | `observability/CostCalculator.java` | 通配解析 + 缓存公式 + requirePricing |
| 修改 | `core/GatewayService.java` | 路由后 fail-close 预检；落库带缓存列 |
| 修改 | `persistence/repository/RequestLogRecord.java` | +缓存两列 |
| 修改 | `persistence/entity/RequestLogEntity.java` | +缓存两列 |
| 修改 | `persistence/repository/impl/RequestLogRepositoryImpl.java` | 写缓存两列 |
| 修改 | `resources/schema.sql` `resources/seed.sql` | 两表加列 + mock* 通配行 + claude 缓存单价 |
| 修改 | `router/RuleBasedRouterTest.java` | 升级阈值用例换稳定文本 |
| 新建(测试) | `TokenEstimatorTest` `UsageTest` `AnthropicUsageTest` `TokenBillingIntegrationTest` | 见各任务 |
| 修改(测试) | `CostCalculatorTest` `OpenAiStreamReadTest` `AnthropicStreamReadTest` | 新用例/新签名 |
| 修改(前端) | `src/views/Pricing.vue` | 缓存单价列 + 表单字段 + 通配提示 |
| 修改(前端) | `src/views/Logs.vue` | Token 列缓存明细 tooltip |

---

### Task 1: jtokkit 依赖 + TokenEstimator 真实分词

**Files:**
- Modify: `pom.xml`
- Rewrite: `src/main/java/com/llm/gateway/provider/TokenEstimator.java`
- Create: `src/test/java/com/llm/gateway/provider/TokenEstimatorTest.java`
- Modify: `src/test/java/com/llm/gateway/router/RuleBasedRouterTest.java`（一处测试数据）

- [x] **Step 1.1: pom.xml 加依赖**（`spring-security-crypto` 依赖之后插入）

```xml
        <!-- jtokkit：tiktoken 的纯 Java 实现，用于 Token 估算兜底（绝不覆盖上游 usage） -->
        <dependency>
            <groupId>com.knuddels</groupId>
            <artifactId>jtokkit</artifactId>
            <version>1.1.0</version>
        </dependency>
```

- [x] **Step 1.2: 写失败测试**

```java
package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.knuddels.jtokkit.api.EncodingType;
import com.llm.gateway.api.dto.ChatMessage;

class TokenEstimatorTest {

    @Test
    void emptyAndNullTextCountZero() {
        assertEquals(0, TokenEstimator.estimate((String) null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void countsEnglishWithRealBpe() {
        // tiktoken 黄金样例："hello world" 在 cl100k 与 o200k 下均为 2 token
        assertEquals(2, TokenEstimator.estimate("gpt-4o", "hello world"));
        assertEquals(2, TokenEstimator.estimate("gpt-3.5-turbo", "hello world"));
    }

    @Test
    void countsChineseReasonably() {
        // 中文按真实 BPE 计数；区间断言避免绑死 jtokkit 词表版本
        int tokens = TokenEstimator.estimate("claude-opus-4-8", "你好，世界！这是一次分词测试。");
        assertTrue(tokens >= 5 && tokens <= 30, "实际: " + tokens);
    }

    @Test
    void selectsEncodingByModel() {
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-3.5-turbo"));
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-4"));
        assertSame(EncodingType.CL100K_BASE, TokenEstimator.encodingTypeFor("gpt-4-turbo"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("gpt-4o"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("gpt-4.1-mini"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("claude-opus-4-8"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor("deepseek-v4-pro"));
        assertSame(EncodingType.O200K_BASE, TokenEstimator.encodingTypeFor(null));
    }

    @Test
    void messagesAddPerMessageOverhead() {
        List<ChatMessage> messages = List.of(ChatMessage.user("hello world"));
        // 2（内容）+ 4（每条消息的对话格式开销）
        assertEquals(6, TokenEstimator.estimate("gpt-4o", messages));
    }
}
```

- [x] **Step 1.3: 运行确认失败**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test -Dtest=TokenEstimatorTest`
Expected: 编译失败（encodingTypeFor 不存在 / jtokkit 类未导入）

- [x] **Step 1.4: 重写 TokenEstimator**（整文件替换）

```java
package com.llm.gateway.provider;

import java.util.List;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.llm.gateway.api.dto.ChatMessage;

/**
 * Token 估算工具：基于 jtokkit（tiktoken 的 Java 实现）做真实 BPE 分词。
 *
 * <p><strong>只用于估算</strong>：上游未返回 usage 时的兜底、流式中断的用量补记、
 * 路由升级阈值判断。上游返回了真实 usage 一律以上游为准，本地估算绝不覆盖——
 * 对账口径以供应商账单为准（参考 sub2api 的实践）。
 *
 * <p>编码选择：gpt-3.5 与旧版 gpt-4（非 4o/4.1）用 CL100K_BASE，其余模型
 * （gpt-4o/4.1/o 系、claude、deepseek、mock 及未知）统一用 O200K_BASE——
 * 非 OpenAI 模型无公开 tokenizer，o200k 是估算场景下最接近的通用近似。
 */
public final class TokenEstimator {

    /** OpenAI chat 格式每条消息的结构开销（role/分隔符）经验值。 */
    private static final int TOKENS_PER_MESSAGE = 4;

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding CL100K = REGISTRY.getEncoding(EncodingType.CL100K_BASE);
    private static final Encoding O200K = REGISTRY.getEncoding(EncodingType.O200K_BASE);

    private TokenEstimator() {
    }

    /**
     * 估算单段文本的 Token 数（无模型上下文，按 O200K 计）。
     *
     * @param text 文本
     * @return 估算 Token 数，空文本为 0
     */
    public static int estimate(String text) {
        return estimate(null, text);
    }

    /**
     * 估算一组消息的总 Token 数（无模型上下文，按 O200K 计）。
     *
     * @param messages 消息列表
     * @return 估算 Token 数（含每条消息的格式开销）
     */
    public static int estimate(List<ChatMessage> messages) {
        return estimate(null, messages);
    }

    /**
     * 按模型选择编码估算单段文本。
     *
     * @param model 物理模型名（可空，空按 O200K）
     * @param text  文本
     * @return 估算 Token 数，空文本为 0
     */
    public static int estimate(String model, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding(encodingTypeFor(model)).countTokens(text);
    }

    /**
     * 按模型选择编码估算一组消息。
     *
     * @param model    物理模型名（可空）
     * @param messages 消息列表
     * @return 估算 Token 数（每条消息加 {@value TOKENS_PER_MESSAGE} 格式开销）
     */
    public static int estimate(String model, List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += TOKENS_PER_MESSAGE + estimate(model, message.content());
        }
        return total;
    }

    /**
     * 模型 → 编码类型（包私有，供测试断言选择规则）。
     *
     * @param model 模型名（可空）
     * @return 编码类型
     */
    static EncodingType encodingTypeFor(String model) {
        if (model != null && (model.startsWith("gpt-3.5")
                || (model.startsWith("gpt-4") && !model.startsWith("gpt-4o") && !model.startsWith("gpt-4.1")))) {
            return EncodingType.CL100K_BASE;
        }
        return EncodingType.O200K_BASE;
    }

    private static Encoding encoding(EncodingType type) {
        return type == EncodingType.CL100K_BASE ? CL100K : O200K;
    }
}
```

- [x] **Step 1.5: 修 RuleBasedRouterTest 的阈值用例**

`shouldEscalateToLargeModelWhenPromptExceedsThreshold` 用的 `"x".repeat(400)` 在旧 4 字符法下是 100 token（>50 阈值），但 BPE 会把连续重复字符压成大块 token，计数不稳定。换成每次重复都产生稳定 token 的文本：

```java
    @Test
    void shouldEscalateToLargeModelWhenPromptExceedsThreshold() {
        // "word " 重复 120 次：BPE 下每个 " word" 约 1 token，稳定超过 50 阈值
        RouteDecision decision = router.route(request("auto", "word ".repeat(120)));

        assertEquals(new ProviderTarget("anthropic", "claude-opus-4-8"), decision.primary());
        assertTrue(decision.fallbacks().contains(new ProviderTarget("mock", "mock-small")));
    }
```

- [x] **Step 1.6: 测试通过 + 关联回归**

Run: `mvn -q test -Dtest='TokenEstimatorTest,RuleBasedRouterTest,MockProviderStreamTest'`
Expected: 全 PASS（MockProvider 用量数字变了但其测试不断言具体值）

---

### Task 2: Usage 缓存字段（只进不出）+ OpenAI cached_tokens 拆分

**Files:**
- Modify: `src/main/java/com/llm/gateway/api/dto/Usage.java`（整文件替换）
- Create: `src/test/java/com/llm/gateway/api/dto/UsageTest.java`
- Modify: `src/test/java/com/llm/gateway/provider/OpenAiStreamReadTest.java`（追加一个用例）

- [x] **Step 2.1: 写失败测试**

`UsageTest.java`：

```java
package com.llm.gateway.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class UsageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesOnlyThreeProtocolFields() {
        String json = mapper.writeValueAsString(Usage.of(100, 20, 30, 10));
        assertTrue(json.contains("\"prompt_tokens\":100"));
        assertTrue(json.contains("\"completion_tokens\":20"));
        assertTrue(json.contains("\"total_tokens\":120"));
        assertFalse(json.contains("cache"), "缓存字段是内部拆分，不得出现在下游协议中，实际: " + json);
    }

    @Test
    void deserializesOpenAiCachedTokensDetails() {
        String json = """
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":30,"audio_tokens":0}}""";
        Usage usage = mapper.readValue(json, Usage.class);
        assertEquals(100, usage.promptTokens());
        assertEquals(30, usage.cacheReadTokens());
        assertEquals(0, usage.cacheCreationTokens());
    }

    @Test
    void toleratesMissingDetails() {
        Usage usage = mapper.readValue(
                "{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}", Usage.class);
        assertEquals(0, usage.cacheReadTokens());
        assertEquals(Usage.of(7, 2), usage);
    }

    @Test
    void factoriesComputeTotals() {
        assertEquals(new Usage(5, 3, 8, 0, 0), Usage.of(5, 3));
        assertEquals(new Usage(50, 3, 53, 30, 10), Usage.of(50, 3, 30, 10));
    }
}
```

`OpenAiStreamReadTest` 追加：

```java
    @Test
    void capturesCachedTokensFromUsageFrame() throws IOException {
        String sse = """
                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[{"index":0,"delta":{"content":"X"},"finish_reason":null}]}

                data: {"id":"c3","object":"chat.completion.chunk","created":1,"model":"gpt","choices":[],"usage":{"prompt_tokens":100,"completion_tokens":2,"total_tokens":102,"prompt_tokens_details":{"cached_tokens":64}}}

                data: [DONE]

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);
        assertEquals(100, usage.promptTokens(), "OpenAI 口径 prompt 已含缓存，直接沿用");
        assertEquals(64, usage.cacheReadTokens(), "缓存读明细从 prompt_tokens_details 拆出");
    }
```

- [x] **Step 2.2: 运行确认失败**

Run: `mvn -q test -Dtest='UsageTest,OpenAiStreamReadTest'`
Expected: 编译失败（Usage 无缓存字段/新工厂）

- [x] **Step 2.3: 重写 Usage**（整文件替换）

```java
package com.llm.gateway.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token 用量统计（OpenAI 协议）。
 *
 * <p><strong>口径钉死</strong>：{@code promptTokens} 恒为「完整输入」（<strong>含</strong>缓存，
 * OpenAI 语义）；两个缓存字段是它的内部拆分（{@code cacheRead + cacheCreation ≤ prompt}），
 * 仅供计费与审计使用，「只进不出」——从上游 JSON 可读入（{@link #fromJson}），
 * 序列化永不输出（{@link JsonIgnore}），下游协议始终保持三字段。
 *
 * <p>Anthropic 的 input_tokens 不含缓存，由其适配器做加法归一化后再构造本对象。
 *
 * @param promptTokens        输入 Token 数（含缓存）
 * @param completionTokens    输出 Token 数
 * @param totalTokens         合计 Token 数
 * @param cacheReadTokens     缓存读 Token 数（内部拆分，不序列化）
 * @param cacheCreationTokens 缓存写 Token 数（内部拆分，不序列化）
 */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("total_tokens") int totalTokens,
        @JsonIgnore int cacheReadTokens,
        @JsonIgnore int cacheCreationTokens) {

    /** OpenAI 的 {@code prompt_tokens_details}（仅取缓存命中数，其余字段忽略）。 */
    public record PromptTokensDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
    }

    /**
     * 反序列化入口：兼容 OpenAI 把缓存命中数嵌在 {@code prompt_tokens_details.cached_tokens} 的形态。
     * OpenAI 自动缓存无「缓存写」概念，cacheCreationTokens 恒 0（Anthropic 走适配器代码构造，不经此处）。
     */
    @JsonCreator
    public static Usage fromJson(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens,
            @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails) {
        int cacheRead = promptTokensDetails == null || promptTokensDetails.cachedTokens() == null
                ? 0 : promptTokensDetails.cachedTokens();
        return new Usage(promptTokens, completionTokens, totalTokens, cacheRead, 0);
    }

    /**
     * 无缓存拆分的用量（既有调用点全部走这里，行为不变）。
     *
     * @param promptTokens     输入 Token 数
     * @param completionTokens 输出 Token 数
     * @return 用量对象
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return of(promptTokens, completionTokens, 0, 0);
    }

    /**
     * 带缓存拆分的用量，自动求和 total。
     *
     * @param promptTokens        输入 Token 数（含缓存）
     * @param completionTokens    输出 Token 数
     * @param cacheReadTokens     缓存读 Token 数
     * @param cacheCreationTokens 缓存写 Token 数
     * @return 用量对象
     */
    public static Usage of(int promptTokens, int completionTokens, int cacheReadTokens, int cacheCreationTokens) {
        return new Usage(promptTokens, completionTokens, promptTokens + completionTokens,
                cacheReadTokens, cacheCreationTokens);
    }
}
```

- [x] **Step 2.4: 扫既有 3 参构造调用点**

Run: `grep -rn "new Usage(" src/main src/test --include=*.java | grep -v "Usage.of\|record Usage"`
仅 `Usage.java` 自身工厂应出现；若测试里有裸 `new Usage(a, b, c)` 调用点，末尾追加 `, 0, 0`。

- [x] **Step 2.5: 测试通过 + 协议回归**

Run: `mvn -q test -Dtest='UsageTest,OpenAiStreamReadTest,ChatCompletionChunkTest,SseWriterTest'`
Expected: 全 PASS（chunk 的 usage 帧序列化形状不变）

---

### Task 3: Anthropic 缓存捕获与加法归一化

**Files:**
- Modify: `src/main/java/com/llm/gateway/provider/AnthropicResponse.java`
- Modify: `src/main/java/com/llm/gateway/provider/AnthropicStreamEvent.java`
- Modify: `src/main/java/com/llm/gateway/provider/AnthropicProvider.java`
- Create: `src/test/java/com/llm/gateway/provider/AnthropicUsageTest.java`
- Modify: `src/test/java/com/llm/gateway/provider/AnthropicStreamReadTest.java`（追加一个用例）

- [x] **Step 3.1: 写失败测试**

`AnthropicUsageTest.java`：

```java
package com.llm.gateway.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.Usage;

class AnthropicUsageTest {

    @Test
    void toUsageAddsCacheIntoPrompt() {
        // Anthropic 口径 input 不含缓存 → 网关口径 prompt = 10 + 5(写) + 40(读) = 55
        AnthropicResponse.AnthropicUsage usage = new AnthropicResponse.AnthropicUsage(10, 7, 5, 40);
        assertEquals(Usage.of(55, 7, 40, 5), usage.toUsage());
    }

    @Test
    void toUsageToleratesNullCacheFields() {
        AnthropicResponse.AnthropicUsage usage = new AnthropicResponse.AnthropicUsage(10, 7, null, null);
        assertEquals(Usage.of(10, 7), usage.toUsage());
    }
}
```

`AnthropicStreamReadTest` 追加：

```java
    @Test
    void capturesCacheTokensAndNormalizesPromptByAddition() throws IOException {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_c","usage":{"input_tokens":20,"output_tokens":1,"cache_creation_input_tokens":100,"cache_read_input_tokens":300}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        List<ChatCompletionChunk> chunks = new ArrayList<>();
        Usage usage = read(sse, chunks);
        assertEquals(420, usage.promptTokens(), "网关口径 prompt 含缓存：20+100+300");
        assertEquals(300, usage.cacheReadTokens());
        assertEquals(100, usage.cacheCreationTokens());
        assertEquals(5, usage.completionTokens());
    }
```

- [x] **Step 3.2: 运行确认失败**

Run: `mvn -q test -Dtest='AnthropicUsageTest,AnthropicStreamReadTest'`
Expected: 编译失败（AnthropicUsage 无 4 参构造/toUsage）

- [x] **Step 3.3: AnthropicResponse.AnthropicUsage 扩展**（替换该嵌套 record，文件头部 import 增加 `com.llm.gateway.api.dto.Usage`）

```java
    /**
     * 用量。Anthropic 口径：{@code input_tokens} <strong>不含</strong>缓存，
     * 缓存读/写是并列字段——与网关口径（prompt 含缓存）相反，用 {@link #toUsage()} 做加法归一化。
     *
     * @param inputTokens              输入 Token（不含缓存）
     * @param outputTokens             输出 Token
     * @param cacheCreationInputTokens 缓存写 Token（可缺席）
     * @param cacheReadInputTokens     缓存读 Token（可缺席）
     */
    public record AnthropicUsage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {

        /** @return 网关口径用量（prompt = input + 缓存写 + 缓存读，明细随行） */
        public Usage toUsage() {
            int cacheCreation = cacheCreationInputTokens == null ? 0 : cacheCreationInputTokens;
            int cacheRead = cacheReadInputTokens == null ? 0 : cacheReadInputTokens;
            return Usage.of(inputTokens + cacheCreation + cacheRead, outputTokens, cacheRead, cacheCreation);
        }
    }
```

- [x] **Step 3.4: AnthropicStreamEvent.StreamUsage 扩展**（替换该嵌套 record）

```java
    /**
     * 流式事件里的用量：与 {@link AnthropicResponse.AnthropicUsage} 不同，字段可缺席
     * （message_delta 的 usage 只有 output_tokens），故全部用可空 Integer。
     */
    record StreamUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens) {
    }
```

- [x] **Step 3.5: AnthropicProvider 两处改动**

(a) `fromAnthropicResponse` 里替换 usage 组装（原 `int inputTokens = ...; int outputTokens = ...;` 两行与 `Usage.of(inputTokens, outputTokens)`）：

```java
        Usage usage = response.usage() == null ? Usage.of(0, 0) : response.usage().toUsage();
        return ChatCompletionResponse.singleMessage(
                id, Instant.now().getEpochSecond(), model,
                text.toString(), finishReason, usage);
```

(b) `readAnthropicStream`：在局部变量区把
```java
        int inputTokens = 0;
```
改为
```java
        int inputTokens = 0;
        int cacheCreationTokens = 0;
        int cacheReadTokens = 0;
```
`message_start` 分支的 usage 捕获块内（`inputTokens = ...` 之后）追加：

```java
                            if (parsed.message().usage().cacheCreationInputTokens() != null) {
                                cacheCreationTokens = parsed.message().usage().cacheCreationInputTokens();
                            }
                            if (parsed.message().usage().cacheReadInputTokens() != null) {
                                cacheReadTokens = parsed.message().usage().cacheReadInputTokens();
                            }
```

`message_stop` 分支的返回改为：

```java
                    return Usage.of(inputTokens + cacheCreationTokens + cacheReadTokens,
                            outputTokens == null ? 0 : outputTokens, cacheReadTokens, cacheCreationTokens);
```

方法末尾截断兜底的返回改为：

```java
        return outputTokens == null ? null
                : Usage.of(inputTokens + cacheCreationTokens + cacheReadTokens,
                        outputTokens, cacheReadTokens, cacheCreationTokens);
```

javadoc 的 `@return` 行同步改为：`用量（prompt 含缓存的网关口径；input/缓存来自 message_start，output 来自最后携带 usage 的事件）；流截断且输出用量未知时为 null`。

- [x] **Step 3.6: 测试通过 + 既有 Anthropic 流回归**

Run: `mvn -q test -Dtest='AnthropicUsageTest,AnthropicStreamReadTest'`
Expected: 全 PASS（既有用例无缓存字段 → 加 0，断言不变）

---

### Task 4: 定价层——schema、通配解析、缓存公式、fail-close 异常

**Files:**
- Modify: `src/main/resources/schema.sql`、`src/main/resources/seed.sql`
- 本地库 DDL（先于测试执行）
- Create: `src/main/java/com/llm/gateway/exception/PricingNotConfiguredException.java`
- Modify: `src/main/java/com/llm/gateway/persistence/repository/PricingRecord.java`
- Modify: `src/main/java/com/llm/gateway/persistence/entity/ModelPricingEntity.java`
- Modify: `src/main/java/com/llm/gateway/persistence/repository/impl/PricingRepositoryImpl.java`
- Rewrite: `src/main/java/com/llm/gateway/observability/CostCalculator.java`
- Rewrite: `src/test/java/com/llm/gateway/observability/CostCalculatorTest.java`

- [x] **Step 4.1: schema.sql / seed.sql / 本地库**

schema.sql `model_pricing` 表 `output_per_1k` 行后插入两列：

```sql
    cache_read_per_1k  DOUBLE   NULL     COMMENT '缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计',
    cache_write_per_1k DOUBLE   NULL     COMMENT '缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计',
```

seed.sql 计费 INSERT 语句之后追加：

```sql
-- mock 系列通配定价（fail-close 下演示/测试模型经此放行）；claude 缓存单价 = 读 0.1×入、写 1.25×入
INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES ('mock*', 0, 0);
UPDATE model_pricing SET cache_read_per_1k = 0.00150, cache_write_per_1k = 0.01875
WHERE model = 'claude-opus-4-8';
```

本地库执行（**必须在跑任何测试前**，且早于 Task 5 的 fail-close 生效，否则既有集成测试的 mock 模型会被拒）：

```bash
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 llm_gateway -e "
ALTER TABLE model_pricing
  ADD COLUMN cache_read_per_1k  DOUBLE NULL COMMENT '缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计',
  ADD COLUMN cache_write_per_1k DOUBLE NULL COMMENT '缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计';
INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES ('mock*', 0, 0);
UPDATE model_pricing SET cache_read_per_1k = 0.00150, cache_write_per_1k = 0.01875 WHERE model = 'claude-opus-4-8';
SHOW COLUMNS FROM model_pricing;"
```

- [x] **Step 4.2: 写失败测试**（CostCalculatorTest 整文件替换）

```java
package com.llm.gateway.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.exception.PricingNotConfiguredException;
import com.llm.gateway.persistence.repository.PricingRecord;
import com.llm.gateway.persistence.repository.PricingRepository;

class CostCalculatorTest {

    /** 内存假仓储：精确行、带缓存单价行、两条通配行（测最长前缀优先）。 */
    private final PricingRepository repository = () -> List.of(
            new PricingRecord("gpt-4o-mini", 0.00015, 0.00060, null, null),
            new PricingRecord("claude-opus-4-8", 0.015, 0.075, 0.0015, 0.01875),
            new PricingRecord("mock*", 0.0, 0.0, null, null),
            new PricingRecord("mock-d*", 0.001, 0.002, null, null));

    private final CostCalculator calculator = new CostCalculator(repository);

    @Test
    void shouldComputeCostFromPricing() {
        // gpt-4o-mini：输入 0.00015/1K，输出 0.00060/1K；各 1000 Token => 0.00075
        assertEquals(0.00075, calculator.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void shouldReturnZeroForUnknownModel() {
        assertEquals(0.0, calculator.cost("unknown-model", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void shouldReturnZeroForNullUsage() {
        assertEquals(0.0, calculator.cost("gpt-4o-mini", null), 1e-9);
    }

    @Test
    void shouldPriceCacheReadSeparately() {
        // prompt=2000 内含缓存读 1000：非缓存 1000×0.015/1k + 缓存读 1000×0.0015/1k
        assertEquals(0.015 + 0.0015,
                calculator.cost("claude-opus-4-8", Usage.of(2000, 0, 1000, 0)), 1e-9);
    }

    @Test
    void shouldPriceCacheWriteSeparately() {
        assertEquals(0.015 + 0.01875,
                calculator.cost("claude-opus-4-8", Usage.of(2000, 0, 0, 1000)), 1e-9);
    }

    @Test
    void shouldFallBackToInputPriceWhenCachePriceUnset() {
        // 未配缓存单价：缓存 token 按 input 价 → 拆分与否总价一致（不会少收）
        double flat = calculator.cost("gpt-4o-mini", Usage.of(2000, 100));
        double split = calculator.cost("gpt-4o-mini", Usage.of(2000, 100, 500, 0));
        assertEquals(flat, split, 1e-9);
    }

    @Test
    void shouldResolveWildcardByLongestPrefix() {
        // mock-dirty 命中更长的 mock-d*（0.001+0.002），而非 mock*（$0）
        assertEquals(0.003, calculator.cost("mock-dirty", Usage.of(1000, 1000)), 1e-9);
        // mock-small 无精确行 → 命中 mock*（$0）
        assertEquals(0.0, calculator.cost("mock-small", Usage.of(1000, 1000)), 1e-9);
        // 精确行优先于通配
        assertEquals(0.00075, calculator.cost("gpt-4o-mini", Usage.of(1000, 1000)), 1e-9);
    }

    @Test
    void requirePricingFailsClosedForUnpricedModel() {
        assertThrows(PricingNotConfiguredException.class, () -> calculator.requirePricing("no-such-model"));
        assertDoesNotThrow(() -> calculator.requirePricing("mock-anything"));
        assertDoesNotThrow(() -> calculator.requirePricing("gpt-4o-mini"));
    }
}
```

- [x] **Step 4.3: 运行确认失败**

Run: `mvn -q test -Dtest=CostCalculatorTest`
Expected: 编译失败（PricingRecord 5 参、requirePricing、异常类不存在）

- [x] **Step 4.4: 实现定价层**

`PricingNotConfiguredException.java`：

```java
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
        super(HttpStatus.UNPROCESSABLE_ENTITY, "pricing_not_configured",
                "模型 [" + model + "] 未配置计费单价，请先在管理端「计费单价」配置（模型名支持尾部 * 通配）");
    }
}
```

`PricingRecord.java`（整文件替换）：

```java
package com.llm.gateway.persistence.repository;

/**
 * 模型计费单价领域记录。
 *
 * <p>{@code model} 支持尾部 {@code *} 通配（如 {@code mock*}）；解析时精确命中优先、
 * 其次最长前缀通配。缓存单价为 null 表示未配置，缓存 Token 退化按 input 单价计。
 *
 * @param model           模型名（可尾部 * 通配）
 * @param inputPer1k      输入每 1K Token 单价
 * @param outputPer1k     输出每 1K Token 单价
 * @param cacheReadPer1k  缓存读每 1K Token 单价（可空）
 * @param cacheWritePer1k 缓存写每 1K Token 单价（可空）
 */
public record PricingRecord(String model, double inputPer1k, double outputPer1k,
                            Double cacheReadPer1k, Double cacheWritePer1k) {
}
```

`ModelPricingEntity.java` 在 `outputPer1k` 字段与其 getter/setter 之后追加（风格一致）：

```java
    /** 缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计。 */
    @TableField("cache_read_per_1k")
    private Double cacheReadPer1k;

    /** 缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计。 */
    @TableField("cache_write_per_1k")
    private Double cacheWritePer1k;
```

```java
    public Double getCacheReadPer1k() {
        return cacheReadPer1k;
    }

    public void setCacheReadPer1k(Double cacheReadPer1k) {
        this.cacheReadPer1k = cacheReadPer1k;
    }

    public Double getCacheWritePer1k() {
        return cacheWritePer1k;
    }

    public void setCacheWritePer1k(Double cacheWritePer1k) {
        this.cacheWritePer1k = cacheWritePer1k;
    }
```

`PricingRepositoryImpl.findAll` 的映射改为：

```java
        return mapper.selectList(null).stream()
                .map(e -> new PricingRecord(e.getModel(),
                        e.getInputPer1k() == null ? 0.0 : e.getInputPer1k(),
                        e.getOutputPer1k() == null ? 0.0 : e.getOutputPer1k(),
                        e.getCacheReadPer1k(),
                        e.getCacheWritePer1k()))
                .toList();
```

`CostCalculator.java`（整文件替换）：

```java
package com.llm.gateway.observability;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.config.ConfigReloadable;
import com.llm.gateway.exception.PricingNotConfiguredException;
import com.llm.gateway.persistence.repository.PricingRecord;
import com.llm.gateway.persistence.repository.PricingRepository;

/**
 * 成本计算器：根据模型单价与 Token 用量算出单次调用的费用（美元）。
 *
 * <p>单价从数据库（{@code model_pricing} 表）加载并缓存，可通过 {@link #reload()} 热刷新。
 * 定价解析两级：精确命中 → 最长前缀通配（模型名尾部 {@code *}）。
 * 请求前用 {@link #requirePricing} 做 fail-close 预检——不知道多少钱就不放行，
 * 杜绝「静默按 $0 计费」（参考 sub2api 的 fail-closed 定价解析）。
 */
@Component
public class CostCalculator implements ConfigReloadable {

    private final PricingRepository pricingRepository;
    private volatile Map<String, PricingRecord> exact;
    private volatile List<PricingRecord> wildcards;

    /**
     * @param pricingRepository 计费仓储（数据库）
     */
    public CostCalculator(PricingRepository pricingRepository) {
        this.pricingRepository = pricingRepository;
        reload();
    }

    @Override
    public void reload() {
        Map<String, PricingRecord> exactMap = new HashMap<>();
        List<PricingRecord> wildcardList = new ArrayList<>();
        for (PricingRecord record : pricingRepository.findAll()) {
            if (record.model() != null && record.model().endsWith("*")) {
                wildcardList.add(record);
            } else {
                exactMap.put(record.model(), record);
            }
        }
        // 更长的通配前缀更具体，优先匹配（mock-d* 先于 mock*）
        wildcardList.sort(Comparator.comparingInt((PricingRecord r) -> r.model().length()).reversed());
        this.exact = Map.copyOf(exactMap);
        this.wildcards = List.copyOf(wildcardList);
    }

    /**
     * 解析模型定价：精确命中优先，其次最长前缀通配。
     *
     * @param model 物理模型名
     * @return 定价记录，未命中为 empty
     */
    public Optional<PricingRecord> resolve(String model) {
        if (model == null) {
            return Optional.empty();
        }
        PricingRecord hit = exact.get(model);
        if (hit != null) {
            return Optional.of(hit);
        }
        for (PricingRecord wildcard : wildcards) {
            if (model.startsWith(wildcard.model().substring(0, wildcard.model().length() - 1))) {
                return Optional.of(wildcard);
            }
        }
        return Optional.empty();
    }

    /**
     * 计费 fail-close 预检：解析不到定价即拒绝（调上游之前调用）。
     *
     * @param model 物理模型名
     * @throws PricingNotConfiguredException 无定价
     */
    public void requirePricing(String model) {
        if (resolve(model).isEmpty()) {
            throw new PricingNotConfiguredException(model);
        }
    }

    /**
     * 计算单次调用成本：非缓存输入、缓存读、缓存写、输出四段分别计价。
     * 缓存单价未配置（null）时按 input 单价计——不会少收，且无缓存 token 时与旧公式完全一致。
     *
     * @param model 物理模型名
     * @param usage Token 用量（可空）
     * @return 成本（美元）；用量为空或无定价（防御，正常已被 fail-close 拦截）时返回 0
     */
    public double cost(String model, Usage usage) {
        if (usage == null) {
            return 0.0;
        }
        PricingRecord price = resolve(model).orElse(null);
        if (price == null) {
            return 0.0;
        }
        int cacheRead = usage.cacheReadTokens();
        int cacheCreation = usage.cacheCreationTokens();
        int nonCacheInput = Math.max(0, usage.promptTokens() - cacheRead - cacheCreation);
        double cacheReadPer1k = price.cacheReadPer1k() == null ? price.inputPer1k() : price.cacheReadPer1k();
        double cacheWritePer1k = price.cacheWritePer1k() == null ? price.inputPer1k() : price.cacheWritePer1k();
        return nonCacheInput / 1000.0 * price.inputPer1k()
                + cacheRead / 1000.0 * cacheReadPer1k
                + cacheCreation / 1000.0 * cacheWritePer1k
                + usage.completionTokens() / 1000.0 * price.outputPer1k();
    }
}
```

- [x] **Step 4.5: 测试通过 + 编译全量**

Run: `mvn -q test -Dtest=CostCalculatorTest && mvn -q test-compile`
Expected: PASS（8/8）+ BUILD SUCCESS（PricingRecord 调用点只有 PricingRepositoryImpl 与本测试，均已改）

---

### Task 5: GatewayService fail-close 接入 + request_log 缓存列落库

**Files:**
- Modify: `src/main/resources/schema.sql`
- 本地库 DDL
- Modify: `src/main/java/com/llm/gateway/persistence/repository/RequestLogRecord.java`
- Modify: `src/main/java/com/llm/gateway/persistence/entity/RequestLogEntity.java`
- Modify: `src/main/java/com/llm/gateway/persistence/repository/impl/RequestLogRepositoryImpl.java`
- Modify: `src/main/java/com/llm/gateway/core/GatewayService.java`

本任务无独立单测（编排逻辑由 Task 6 集成测试覆盖），以编译 + 全量既有测试回归为门槛。

- [x] **Step 5.1: schema.sql + 本地库加列**

schema.sql `request_log` 表 `completion_tokens` 行后插入：

```sql
    cache_read_tokens     INT        NOT NULL DEFAULT 0 COMMENT '缓存读 Token 数（prompt_tokens 的内部拆分）',
    cache_creation_tokens INT        NOT NULL DEFAULT 0 COMMENT '缓存写 Token 数（prompt_tokens 的内部拆分）',
```

本地库：

```bash
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 llm_gateway -e "
ALTER TABLE request_log
  ADD COLUMN cache_read_tokens     INT NOT NULL DEFAULT 0 COMMENT '缓存读 Token 数（prompt_tokens 的内部拆分）' AFTER completion_tokens,
  ADD COLUMN cache_creation_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存写 Token 数（prompt_tokens 的内部拆分）' AFTER cache_read_tokens;
SHOW COLUMNS FROM request_log;"
```

- [x] **Step 5.2: RequestLogRecord 新签名**（整文件替换）

```java
package com.llm.gateway.persistence.repository;

/**
 * 请求日志领域记录（写入数据库的一行审计/用量记录）。
 *
 * @param requestId           请求 ID
 * @param tenant              租户
 * @param requestedModel      请求的模型/别名
 * @param servedModel         实际产出响应的模型（失败时可空）
 * @param promptTokens        输入 Token（含缓存）
 * @param completionTokens    输出 Token
 * @param totalTokens         总 Token
 * @param cacheReadTokens     缓存读 Token（prompt 的内部拆分；估算路径恒 0）
 * @param cacheCreationTokens 缓存写 Token（prompt 的内部拆分；估算路径恒 0）
 * @param costUsd             成本（美元）
 * @param cacheHit            是否命中缓存
 * @param status              状态：{@code success} / {@code cache_hit} / {@code error}
 *                            / {@code client_aborted} / {@code guardrail_truncated}
 * @param errorCode           错误码（失败时）
 * @param latencyMs           端到端耗时（毫秒）
 */
public record RequestLogRecord(String requestId, String tenant, String requestedModel, String servedModel,
                               int promptTokens, int completionTokens, int totalTokens,
                               int cacheReadTokens, int cacheCreationTokens, double costUsd,
                               boolean cacheHit, String status, String errorCode, long latencyMs) {
}
```

- [x] **Step 5.3: RequestLogEntity 加字段**（`completionTokens` 字段后插字段，getter/setter 区对应位置插方法；驼峰自动映射 `cache_read_tokens`/`cache_creation_tokens`，无数字歧义不需 @TableField）

```java
    /** 缓存读 Token 数（prompt_tokens 的内部拆分）。 */
    private Integer cacheReadTokens;

    /** 缓存写 Token 数（prompt_tokens 的内部拆分）。 */
    private Integer cacheCreationTokens;
```

```java
    public Integer getCacheReadTokens() {
        return cacheReadTokens;
    }

    public void setCacheReadTokens(Integer cacheReadTokens) {
        this.cacheReadTokens = cacheReadTokens;
    }

    public Integer getCacheCreationTokens() {
        return cacheCreationTokens;
    }

    public void setCacheCreationTokens(Integer cacheCreationTokens) {
        this.cacheCreationTokens = cacheCreationTokens;
    }
```

- [x] **Step 5.4: RequestLogRepositoryImpl.save** 在 `setCompletionTokens` 后追加：

```java
        entity.setCacheReadTokens(record.cacheReadTokens());
        entity.setCacheCreationTokens(record.cacheCreationTokens());
```

- [x] **Step 5.5: GatewayService 三处 RequestLogRecord 调用点 + fail-close**

import 区追加 `com.llm.gateway.provider.ProviderTarget`。

(a) `complete()` 路由行后插入 fail-close（注释序号顺延）：

```java
            // 6. 路由：选首选 + 降级链
            RouteDecision decision = router.route(request);
            // 6.5 计费 fail-close：链上任一目标无定价即拒绝，请求不打上游（参考 sub2api 的 fail-closed 定价）
            for (ProviderTarget target : decision.chain()) {
                costCalculator.requirePricing(target.model());
            }
```

(b) `completeStream()` 路由行后同样插入：

```java
            // 6-7. 路由 + 流式容错执行：逐帧「聚合（含增量出站护栏）→ 写出」
            RouteDecision decision = router.route(request);
            // 计费 fail-close：发生在首帧之前，依懒提交设计仍返回 JSON 错误
            for (ProviderTarget target : decision.chain()) {
                costCalculator.requirePricing(target.model());
            }
```

(c) `finish()` 落库改为（usage 已在方法头取出）：

```java
        requestLogRepository.save(new RequestLogRecord(
                context.requestId(), context.tenant(), context.requestedModel(), response.model(),
                promptTokens, completionTokens, totalTokens,
                usage == null ? 0 : usage.cacheReadTokens(),
                usage == null ? 0 : usage.cacheCreationTokens(),
                cost, cacheHit, cacheHit ? "cache_hit" : "success", null, latencyMs));
```

(d) `persistPartial()` 落库改为（估算路径无缓存明细，恒 0）：

```java
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(), context.tenant(), context.requestedModel(), servedModel,
                    promptTokens, completionTokens, usage.totalTokens(), 0, 0, cost, false,
                    status, errorCode, latencyMs));
```

(e) `persistError()` 落库改为：

```java
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(), context.tenant(), context.requestedModel(), null,
                    0, 0, 0, 0, 0, 0.0, false, "error", e.code(), context.elapsedMillis(now)));
```

- [x] **Step 5.6: 编译 + 全量回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS 全绿。既有集成测试的 mock 系列模型经 Task 4.1 插入的 `mock*` 通配行放行；若有 `guardrailTruncatesMidStreamWithErrorFrame` 等用例失败，先确认本地库 `model_pricing` 里 `mock*` 行存在。

---

### Task 6: 计费集成测试（fail-close + 通配放行 + 缓存列落库）

**Files:**
- Create: `src/test/java/com/llm/gateway/api/TokenBillingIntegrationTest.java`

- [x] **Step 6.1: 写集成测试**（组合行为验证；Task 5 正确则直接通过，属正常）

```java
package com.llm.gateway.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.config.ConfigRefreshService;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenBillingIntegrationTest {

    private static final String TEST_KEY = "sk-it-billing-0000000000000001";
    private static final String TENANT = "it-billing";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private ConfigRefreshService refreshService;

    @BeforeAll
    void setUp() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO api_key (key_hash, key_prefix, tenant, roles, allowed_models, enabled)
                VALUES (SHA2(?, 256), ?, ?, 'user', '*', 1)
                """, TEST_KEY, TEST_KEY.substring(0, 12), TENANT);
        // 兜底保证通配行存在（seed 已含；幂等）
        jdbcTemplate.update(
                "INSERT IGNORE INTO model_pricing (model, input_per_1k, output_per_1k) VALUES ('mock*', 0, 0)");
        // 无定价的路由目标：别名 it-unpriced → mock 供应商的 zz-unpriced-model（不匹配任何定价行）
        jdbcTemplate.update("""
                INSERT IGNORE INTO routing_rule (alias, primary_provider, primary_model)
                VALUES ('it-unpriced', 'mock', 'zz-unpriced-model')
                """);
        refreshService.reloadAll();
        apiKeyService.reload();
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM api_key WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
        jdbcTemplate.update("DELETE FROM routing_rule WHERE alias = 'it-unpriced'");
        refreshService.reloadAll();
        apiKeyService.reload();
    }

    /** 每次唯一 content，避免缓存命中干扰。 */
    private String body(String model, boolean stream) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"b-"
                + UUID.randomUUID() + "\"}]" + (stream ? ",\"stream\":true" : "") + "}";
    }

    @Test
    void unpricedModelRejectedBeforeUpstreamNonStream() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("pricing_not_configured"));
    }

    @Test
    void unpricedModelRejectedAsJsonEvenWhenStreaming() throws Exception {
        // fail-close 发生在首帧之前 → 懒提交保证仍是 JSON 错误而非 SSE
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", true)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("pricing_not_configured"));
    }

    @Test
    void rejectionIsAuditedWithErrorCode() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("it-unpriced", false)))
                .andExpect(status().isUnprocessableEntity());
        String errorCode = jdbcTemplate.queryForObject(
                "SELECT error_code FROM request_log WHERE tenant = ? AND status = 'error' ORDER BY id DESC LIMIT 1",
                String.class, TENANT);
        assertEquals("pricing_not_configured", errorCode);
    }

    @Test
    void wildcardPricedMockModelPassesAndPersistsCacheColumns() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mock-billing-it", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.total_tokens").isNumber())
                .andExpect(jsonPath("$.usage.prompt_tokens_details").doesNotExist());
        Integer cacheRead = jdbcTemplate.queryForObject(
                "SELECT cache_read_tokens FROM request_log WHERE tenant = ? ORDER BY id DESC LIMIT 1",
                Integer.class, TENANT);
        assertEquals(0, cacheRead, "mock 无缓存，缓存列应落 0 而非 NULL");
    }
}
```

实现注意：
- `routing_rule.alias` 有唯一键，INSERT IGNORE 保证残留时幂等；cleanup 删除。
- 路由仓储若给规则补默认降级目标（含已定价模型），fail-close 是「链上全部目标必须有价」，首选 `zz-unpriced-model` 无价即拒——断言不受降级链影响。
- `$.usage.prompt_tokens_details` doesNotExist 是「只进不出」的端到端回归。

- [x] **Step 6.2: 运行**

Run: `mvn -q test -Dtest=TokenBillingIntegrationTest`
Expected: PASS（4/4）。若 `unpriced...` 用例返回 200：检查 ConfigRefreshService 是否刷新了路由规则缓存（必要时改为额外调用具体 reload 组件）。

- [x] **Step 6.3: 全量回归**

Run: `mvn -q test`
Expected: 全绿（既有 97 + 本子项目新增）

---

### Task 7: 前端 Pricing 缓存单价 + Logs 缓存明细

**Files:**（全部在 C:/practice/llm-gateway-ui）
- Modify: `src/views/Pricing.vue`
- Modify: `src/views/Logs.vue`

- [x] **Step 7.1: Pricing.vue**

(a) `blankForm` 改为：

```javascript
    blankForm: () => ({ id: null, model: '', inputPer1k: 0, outputPer1k: 0, cacheReadPer1k: null, cacheWritePer1k: null }),
```

(b) 表格「输出 / 1K (USD)」列之后追加两列：

```html
        <el-table-column label="缓存读 / 1K (USD)" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums">{{ row.cacheReadPer1k == null ? '—' : '$' + Number(row.cacheReadPer1k).toFixed(5) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="缓存写 / 1K (USD)" width="160" align="right">
          <template #default="{ row }">
            <span class="tabular-nums">{{ row.cacheWritePer1k == null ? '—' : '$' + Number(row.cacheWritePer1k).toFixed(5) }}</span>
          </template>
        </el-table-column>
```

(c) 模型输入框 placeholder 换成通配提示：

```html
          <el-input v-model="form.model" placeholder="deepseek-v4-pro 或 mock*（尾部通配）" :disabled="dialog.mode === 'edit'" />
```

(d) 对话框「输出 / 1K (USD)」表单项之后追加两项（可空：留空 = 按 input 单价计）：

```html
        <el-form-item label="缓存读 / 1K (USD)">
          <el-input-number v-model="form.cacheReadPer1k" :precision="5" :step="0.0001" :min="0" controls-position="right" style="width:100%" placeholder="留空按输入单价" />
        </el-form-item>
        <el-form-item label="缓存写 / 1K (USD)">
          <el-input-number v-model="form.cacheWritePer1k" :precision="5" :step="0.0001" :min="0" controls-position="right" style="width:100%" placeholder="留空按输入单价" />
        </el-form-item>
```

- [x] **Step 7.2: Logs.vue Token 列加缓存 tooltip**（替换「Token(入/出/合)」列）

```html
        <el-table-column label="Token(入/出/合)" width="170" align="right">
          <template #default="{ row }">
            <el-tooltip v-if="(row.cacheReadTokens || 0) + (row.cacheCreationTokens || 0) > 0"
                        :content="`缓存读 ${row.cacheReadTokens || 0} / 缓存写 ${row.cacheCreationTokens || 0}`"
                        placement="top">
              <span class="tabular-nums has-cache">{{ row.promptTokens }}/{{ row.completionTokens }}/<b>{{ row.totalTokens }}</b></span>
            </el-tooltip>
            <span v-else class="tabular-nums">{{ row.promptTokens }}/{{ row.completionTokens }}/<b>{{ row.totalTokens }}</b></span>
          </template>
        </el-table-column>
```

`<style scoped>` 里追加：

```css
.has-cache { text-decoration: underline dotted; cursor: help; }
```

- [x] **Step 7.3: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功

---

### Task 8: 全量回归 + 真机冒烟

**Files:** 无新增（验证任务）

- [x] **Step 8.1: 后端全量测试**

Run: `cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && mvn -q test`
Expected: 全绿

- [x] **Step 8.2: 起服务冒烟**

后台起服务：

```bash
cd C:/practice/llm-gateway && export JAVA_HOME=~/.jdks/ms-21.0.10 && \
GATEWAY_JWT_SECRET=smoke-secret-0123456789abcdef012345 mvn -q spring-boot:run
```

就绪后（请求体写临时文件再 --data-binary，避免 Windows 命令行中文编码问题）：

```bash
c() { curl -s --noproxy '*' "$@"; }
# 1. mock 通配放行：非流式 200 + usage 三字段（无 prompt_tokens_details）
printf '{"model":"mock-billing-smoke","messages":[{"role":"user","content":"billing-smoke-1"}]}' > /tmp/b1.json
c -X POST localhost:8080/v1/chat/completions -H "Authorization: Bearer sk-demo-tenant-a" \
  -H "Content-Type: application/json" --data-binary @/tmp/b1.json
# 2. 流式含 usage 帧仍三字段
printf '{"model":"mock-billing-smoke","messages":[{"role":"user","content":"billing-smoke-2"}],"stream":true,"stream_options":{"include_usage":true}}' > /tmp/b2.json
c -N -X POST localhost:8080/v1/chat/completions -H "Authorization: Bearer sk-demo-tenant-a" \
  -H "Content-Type: application/json" --data-binary @/tmp/b2.json
# 3. 观察日志行 tokens 数值应为 BPE 计数（不再是字符数/4）
```

DB 抽查缓存列与新单价列：

```bash
"C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql" -uroot -p123456 --default-character-set=utf8mb4 llm_gateway -e "
SELECT request_id, prompt_tokens, cache_read_tokens, cache_creation_tokens, cost_usd, status
FROM request_log ORDER BY id DESC LIMIT 4;
SELECT model, input_per_1k, cache_read_per_1k, cache_write_per_1k FROM model_pricing;"
```

Expected: 新列存在且 mock 请求缓存列为 0；model_pricing 含 `mock*` 行与 claude 缓存单价。冒烟完成后停服务。

- [x] **Step 8.3: 管理端手测**（可选，如用户在场）

`npm run dev` → 登录 → 计费单价页看到缓存两列、能新增 `xx*` 通配行；请求日志页 Token 列悬停无缓存时无下划线。

---

## 自审记录（writing-plans Self-Review）

1. **Spec 覆盖**：§3 Usage 扩展（Task 2）、§4 供应商归一化（Task 2 OpenAI / Task 3 Anthropic / Mock 不动）、§5 定价通配+fail-close（Task 4/5/6）、§6 成本公式（Task 4）、§7 jtokkit（Task 1）、§8 落库与管理端（Task 5/7）、§9 测试策略（各任务 + Task 6/8）、§10 实现顺序一致 ✔
2. **占位符扫描**：无 TBD；Task 6 对 ConfigRefreshService 刷新路由缓存的「若失败则改调具体组件」是显式的实施期核对指令 ✔
3. **类型一致性**：`Usage.of(p,c,cacheRead,cacheCreation)` 参数序在 Task 2 定义、Task 3/4 使用一致；`PricingRecord` 5 参在 Task 4 定义与测试一致；`RequestLogRecord` 14 参在 Task 5 (b)–(e) 全部调用点展示；`requirePricing`/`resolve`/`encodingTypeFor` 命名各任务一致 ✔
4. **顺序风险**：Task 4.1 必须先插 `mock*` 行再进 Task 5（fail-close 生效），已在 Step 4.1 与 5.6 双向注明 ✔
