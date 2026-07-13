# llm-gateway 响应缓存 Redis 化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 精确匹配响应缓存支持可选的 Redis 后端(配置开关,默认内存),缓存跨重启/多实例生效,并解决「Usage 序列化往返丢缓存拆分」backlog。

**Architecture:** 新配置 `gateway.cache.store: memory|redis` 用 `@ConditionalOnProperty` 在现有 `ExactMatchCache` 与新增 `RedisResponseCache` 间二选一装配;Redis 值为信封 record `CachedResponse` 的 JSON(平铺保留 Usage 的两个 `@JsonIgnore` 拆分字段);Redis 任何故障 fail-open(当未命中,WARN 日志)。`CacheService`/`GatewayService` 零改动。

**Tech Stack:** Spring Boot 4.1(注意:Jackson 3,包名 `tools.jackson`,异常为 unchecked)、spring-boot-starter-data-redis(Lettuce)、Mockito、docker compose。

**Spec:** `docs/superpowers/specs/2026-07-13-llm-gateway-redis-cache-design.md`

**构建命令(重要):** 本仓库 mvnw 已损坏,统一用系统 Maven + 指定 JDK:
```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```
本机 curl 必须加 `--noproxy '*'`。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `llm-gateway/pom.xml` | 修改 | 加 data-redis starter |
| `llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java` | 修改 | `Cache` record 加 `store` 字段 |
| `llm-gateway/src/test/java/com/llm/gateway/Fixtures.java` | 修改 | 适配新 `Cache` 构造 |
| `llm-gateway/src/main/resources/application.yaml` | 修改 | `store: memory` 默认、redis 连接、关 redis health |
| `llm-gateway/src/main/resources/application-prod.yaml` | 修改 | `store` 由环境变量覆盖 |
| `llm-gateway/src/main/java/com/llm/gateway/cache/ExactMatchCache.java` | 修改 | 加 `@ConditionalOnProperty`(memory,缺省匹配) |
| `llm-gateway/src/main/java/com/llm/gateway/cache/CachedResponse.java` | 新建 | Redis 存储信封:保留 Usage 拆分 |
| `llm-gateway/src/main/java/com/llm/gateway/cache/RedisResponseCache.java` | 新建 | Redis 实现,fail-open |
| `llm-gateway/src/test/java/com/llm/gateway/cache/CachedResponseTest.java` | 新建 | 信封 JSON 往返测试 |
| `llm-gateway/src/test/java/com/llm/gateway/cache/RedisResponseCacheTest.java` | 新建 | mock RedisTemplate 单测 |
| `llm-gateway/src/test/java/com/llm/gateway/cache/CacheStoreWiringTest.java` | 新建 | 条件装配测试 |
| `llm-gateway/docker-compose.yml` | 修改 | 加 redis 服务、gateway 环境变量 |
| `README.md`(仓库根) | 修改 | 缓存 bullet 提 Redis |

---

### Task 1: 依赖与配置骨架

**Files:**
- Modify: `llm-gateway/pom.xml`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java`
- Modify: `llm-gateway/src/test/java/com/llm/gateway/Fixtures.java`
- Modify: `llm-gateway/src/main/resources/application.yaml`
- Modify: `llm-gateway/src/main/resources/application-prod.yaml`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/cache/ExactMatchCache.java`

本任务无新行为(默认 memory 与现状等价),验收标准 = 现有 146 个测试全绿。

- [ ] **Step 1: pom.xml 加依赖**

在 `micrometer-registry-prometheus` 依赖块之后插入:

```xml
        <!-- Redis 响应缓存(可选,gateway.cache.store=redis 时启用);Lettuce 客户端 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
```

- [ ] **Step 2: GatewayProperties.Cache 加 store 字段**

`Cache` record 改为(注意 `store` 放 `enabled` 之后;该字段仅由 `@ConditionalOnProperty` 从 Environment 读取,代码不直接消费,放进 record 是让配置形状有单一权威定义):

```java
    /**
     * 缓存配置。
     *
     * @param enabled    是否启用缓存
     * @param store      缓存后端:memory(默认)/ redis。由 @ConditionalOnProperty 消费,选择 ResponseCache 实现
     * @param ttlSeconds 缓存条目存活秒数
     * @param semantic   语义缓存子配置
     */
    public record Cache(boolean enabled, String store, long ttlSeconds, Semantic semantic) {
```

(`Semantic` 子 record 不变。)

- [ ] **Step 3: Fixtures 适配**

`Fixtures.properties(...)` 中 `new Cache(true, cacheTtlSeconds, ...)` 改为:

```java
                new Cache(true, "memory", cacheTtlSeconds, new Cache.Semantic(false, 0.92)),
```

- [ ] **Step 4: application.yaml 三处修改**

(1) `spring:` 下(`jackson:` 块之后、`datasource:` 之前)加:

```yaml
  # Redis 连接(仅 gateway.cache.store=redis 时实际使用;Lettuce 惰性连接,memory 模式不连)
  # timeout 1s:Redis 挂死时请求最多等 1s,配合 fail-open 不拖垮主链路
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 1s
```

(2) `management:` 下加(与 `endpoint:` 同级):

```yaml
  # 缓存 fail-open:Redis 挂了网关照常服务,不能让 health 整体 DOWN 误导 compose healthcheck
  health:
    redis:
      enabled: false
```

(3) `gateway.cache:` 下加 `store`:

```yaml
  cache:
    enabled: true
    # 缓存后端:memory 单机内存 / redis(跨重启、多实例共享)
    store: memory
    ttl-seconds: 300
```

- [ ] **Step 5: application-prod.yaml 加覆盖**

`gateway:` 下加:

```yaml
  cache:
    # compose 部署设 GATEWAY_CACHE_STORE=redis;不设则与开发一致走内存
    store: ${GATEWAY_CACHE_STORE:memory}
```

- [ ] **Step 6: ExactMatchCache 加条件注解**

`@Component` 上方/下方加(import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`):

```java
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "memory", matchIfMissing = true)
public class ExactMatchCache implements ResponseCache {
```

类 javadoc 中「生产环境多实例时应替换为 Redis 实现——接口 {@link ResponseCache} 已为此预留」改为「多实例/跨重启场景配置 {@code gateway.cache.store=redis} 切换到 {@link RedisResponseCache}」。(此时 RedisResponseCache 尚不存在,javadoc 的 `{@link}` 会编译告警,先写成 `{@code RedisResponseCache}`,Task 3 建类后再改回 `{@link}`。)

- [ ] **Step 7: 跑全量测试确认全绿**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```

Expected: `BUILD SUCCESS`,146 tests,0 failures。

- [ ] **Step 8: Commit**

```bash
cd C:/practice && git add llm-gateway/pom.xml llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java llm-gateway/src/test/java/com/llm/gateway/Fixtures.java llm-gateway/src/main/resources/application.yaml llm-gateway/src/main/resources/application-prod.yaml llm-gateway/src/main/java/com/llm/gateway/cache/ExactMatchCache.java
git commit -m "feat: 缓存后端配置骨架(gateway.cache.store,默认 memory 行为不变)"
```

---

### Task 2: CachedResponse 信封(清 Usage 拆分 backlog)

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/cache/CachedResponse.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/cache/CachedResponseTest.java`

背景:`Usage.cacheReadTokens`/`cacheCreationTokens` 是 `@JsonIgnore`(对外协议只出三字段),`ChatCompletionResponse` 直接 JSON 往返会丢拆分。信封把这两个值平铺存在响应旁边,读取时重建。

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

import tools.jackson.databind.ObjectMapper;

class CachedResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldPreserveUsageSplitAcrossJsonRoundTrip() {
        ChatCompletionResponse original = response(Usage.of(10, 5, 4, 2));

        String json = mapper.writeValueAsString(CachedResponse.of(original));
        // 对外协议不变:usage 内不出现拆分字段(它们平铺在信封上)
        assertFalse(json.contains("cache_read"));
        ChatCompletionResponse restored = mapper.readValue(json, CachedResponse.class).toResponse();

        assertEquals(4, restored.usage().cacheReadTokens());
        assertEquals(2, restored.usage().cacheCreationTokens());
        assertEquals(10, restored.usage().promptTokens());
        assertEquals(15, restored.usage().totalTokens());
        assertEquals("hello", restored.firstContent());
    }

    @Test
    void shouldPreserveUpstreamTotalEvenWhenInconsistent() {
        // 上游给的 total 与 p+c 不一致时原样保留(Usage 契约:补缺不是重算)
        ChatCompletionResponse original = response(new Usage(10, 5, 99, 4, 2));

        String json = mapper.writeValueAsString(CachedResponse.of(original));
        ChatCompletionResponse restored = mapper.readValue(json, CachedResponse.class).toResponse();

        assertEquals(99, restored.usage().totalTokens());
        assertEquals(4, restored.usage().cacheReadTokens());
    }

    @Test
    void shouldPassThroughNullUsage() {
        ChatCompletionResponse original = response(null);

        CachedResponse envelope = CachedResponse.of(original);
        assertEquals(0, envelope.cacheReadTokens());

        String json = mapper.writeValueAsString(envelope);
        assertNull(mapper.readValue(json, CachedResponse.class).toResponse().usage());
    }

    @Test
    void shouldReturnSameResponseWhenNoSplit() {
        ChatCompletionResponse original = response(Usage.of(10, 5));
        assertSame(original, CachedResponse.of(original).toResponse());
    }

    private ChatCompletionResponse response(Usage usage) {
        return ChatCompletionResponse.singleMessage("id-1", 123L, "mock-small", "hello", "stop", usage);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test -Dtest=CachedResponseTest
```

Expected: COMPILATION ERROR(`CachedResponse` 不存在)。

- [ ] **Step 3: 实现 CachedResponse**

```java
package com.llm.gateway.cache;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

/**
 * Redis 缓存的存储信封。
 *
 * <p>{@link Usage} 的缓存拆分字段是 {@code @JsonIgnore}（对外协议只出三字段），响应直接 JSON
 * 往返会丢拆分。信封把两个拆分值平铺存在响应旁边，读取时重建——对外序列化行为零改动。
 *
 * @param response            响应（正常 Jackson 序列化，usage 只出三字段）
 * @param cacheReadTokens     从 usage 摘出的缓存读拆分
 * @param cacheCreationTokens 从 usage 摘出的缓存写拆分
 */
public record CachedResponse(
        ChatCompletionResponse response,
        int cacheReadTokens,
        int cacheCreationTokens) {

    /**
     * 包装响应，摘出 Usage 拆分。
     *
     * @param response 待缓存的响应
     * @return 信封
     */
    public static CachedResponse of(ChatCompletionResponse response) {
        Usage usage = response.usage();
        return new CachedResponse(response,
                usage == null ? 0 : usage.cacheReadTokens(),
                usage == null ? 0 : usage.cacheCreationTokens());
    }

    /**
     * 还原响应：有拆分则用信封值重建 Usage（total 原样保留，不重算）。
     *
     * @return 还原后的响应
     */
    public ChatCompletionResponse toResponse() {
        Usage usage = response.usage();
        if (usage == null || (cacheReadTokens == 0 && cacheCreationTokens == 0)) {
            return response;
        }
        Usage rebuilt = new Usage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                cacheReadTokens, cacheCreationTokens);
        return new ChatCompletionResponse(response.id(), response.object(), response.created(),
                response.model(), response.choices(), rebuilt);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test -Dtest=CachedResponseTest
```

Expected: PASS,4 tests。

- [ ] **Step 5: Commit**

```bash
cd C:/practice && git add llm-gateway/src/main/java/com/llm/gateway/cache/CachedResponse.java llm-gateway/src/test/java/com/llm/gateway/cache/CachedResponseTest.java
git commit -m "feat: CachedResponse 存储信封,JSON 往返保留 Usage 缓存拆分(清 backlog)"
```

---

### Task 3: RedisResponseCache(fail-open)

**Files:**
- Create: `llm-gateway/src/main/java/com/llm/gateway/cache/RedisResponseCache.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/cache/RedisResponseCacheTest.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/cache/ExactMatchCache.java`(javadoc `{@code}` 改回 `{@link RedisResponseCache}`)

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.llm.gateway.Fixtures;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.Usage;

import tools.jackson.databind.ObjectMapper;

class RedisResponseCacheTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    // Fixtures 默认 TTL 300s
    private final RedisResponseCache cache =
            new RedisResponseCache(template, new ObjectMapper(), Fixtures.properties());

    @BeforeEach
    void setUp() {
        when(template.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void shouldRoundTripThroughRedisJsonWithKeyPrefixAndTtl() {
        ChatCompletionResponse response = ChatCompletionResponse.singleMessage(
                "id-1", 123L, "mock-small", "hello", "stop", Usage.of(10, 5, 4, 2));
        cache.put("abc", response);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("gw:cache:exact:abc"), json.capture(), eq(Duration.ofSeconds(300)));

        when(valueOps.get("gw:cache:exact:abc")).thenReturn(json.getValue());
        Optional<ChatCompletionResponse> restored = cache.get("abc");

        assertTrue(restored.isPresent());
        assertEquals("hello", restored.get().firstContent());
        assertEquals(4, restored.get().usage().cacheReadTokens());
        assertEquals(2, restored.get().usage().cacheCreationTokens());
    }

    @Test
    void shouldReturnEmptyOnMiss() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertTrue(cache.get("missing").isEmpty());
    }

    @Test
    void shouldFailOpenWhenRedisGetThrows() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("connection refused"));
        assertTrue(cache.get("abc").isEmpty());
    }

    @Test
    void shouldFailOpenOnCorruptJson() {
        when(valueOps.get(anyString())).thenReturn("not-json{");
        assertTrue(cache.get("abc").isEmpty());
    }

    @Test
    void shouldFailOpenWhenRedisPutThrows() {
        doThrow(new RuntimeException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), eq(Duration.ofSeconds(300)));
        ChatCompletionResponse response = ChatCompletionResponse.singleMessage(
                "id-1", 123L, "mock-small", "hello", "stop", Usage.of(1, 1));

        assertDoesNotThrow(() -> cache.put("abc", response));
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test -Dtest=RedisResponseCacheTest
```

Expected: COMPILATION ERROR(`RedisResponseCache` 不存在)。

- [ ] **Step 3: 实现 RedisResponseCache**

注意:Jackson 3 的 `readValue`/`writeValueAsString` 抛 unchecked 的 `JacksonException`(RuntimeException 子类),Lettuce/Spring Data 异常也是 RuntimeException——统一 `catch (RuntimeException)` 即覆盖连接失败、超时、坏 JSON 三类。

```java
package com.llm.gateway.cache;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.config.GatewayProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 基于 Redis 的精确缓存：跨重启、多实例共享，TTL 用 Redis 原生过期（SET ... EX）。
 *
 * <p><strong>fail-open</strong>：缓存是加速器不是依赖——Redis 连接失败、超时、数据损坏一律
 * 记 WARN 并按未命中处理，请求照常打上游；命令超时由 {@code spring.data.redis.timeout} 兜底。
 * 存储值为 {@link CachedResponse} 信封的 JSON（保留 Usage 缓存拆分）。
 */
@Component
@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "redis")
public class RedisResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCache.class);

    /** key 前缀，与其它业务共用 Redis 时隔离命名空间。 */
    static final String KEY_PREFIX = "gw:cache:exact:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    /**
     * @param redisTemplate Redis 模板（字符串键值）
     * @param objectMapper  Spring 管理的 ObjectMapper（与线上协议序列化语义一致）
     * @param properties    网关配置，提供 TTL
     */
    public RedisResponseCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              GatewayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(properties.cache().ttlSeconds());
    }

    @Override
    public Optional<ChatCompletionResponse> get(String key) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, CachedResponse.class).toResponse());
        } catch (RuntimeException e) {
            log.warn("Redis 缓存读取失败,按未命中处理(fail-open): {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, ChatCompletionResponse response) {
        try {
            String json = objectMapper.writeValueAsString(CachedResponse.of(response));
            redisTemplate.opsForValue().set(KEY_PREFIX + key, json, ttl);
        } catch (RuntimeException e) {
            log.warn("Redis 缓存写入失败,本次不缓存(fail-open): {}", e.getMessage());
        }
    }
}
```

同时把 `ExactMatchCache` javadoc 里 Task 1 留下的 `{@code RedisResponseCache}` 改回 `{@link RedisResponseCache}`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test -Dtest=RedisResponseCacheTest
```

Expected: PASS,5 tests。

- [ ] **Step 5: Commit**

```bash
cd C:/practice && git add llm-gateway/src/main/java/com/llm/gateway/cache/RedisResponseCache.java llm-gateway/src/test/java/com/llm/gateway/cache/RedisResponseCacheTest.java llm-gateway/src/main/java/com/llm/gateway/cache/ExactMatchCache.java
git commit -m "feat: RedisResponseCache——Redis 精确缓存实现,故障 fail-open"
```

---

### Task 4: 条件装配测试

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/cache/CacheStoreWiringTest.java`

两个实现类已带 `@ConditionalOnProperty`(Task 1/3),本任务只补装配行为的回归测试。

- [ ] **Step 1: 写测试**

```java
package com.llm.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.llm.gateway.Fixtures;
import com.llm.gateway.config.GatewayProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * gateway.cache.store 的条件装配:memory(含缺省)装 ExactMatchCache,redis 装 RedisResponseCache,
 * 任何取值下 ResponseCache 有且只有一个实现。
 */
class CacheStoreWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(GatewayProperties.class, Fixtures::properties)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withUserConfiguration(ExactMatchCache.class, RedisResponseCache.class);

    @Test
    void shouldDefaultToMemoryWhenStoreUnset() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(ExactMatchCache.class);
        });
    }

    @Test
    void shouldWireMemoryExplicitly() {
        runner.withPropertyValues("gateway.cache.store=memory").run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(ExactMatchCache.class);
        });
    }

    @Test
    void shouldWireRedisWhenConfigured() {
        runner.withPropertyValues("gateway.cache.store=redis").run(ctx -> {
            assertThat(ctx).hasSingleBean(ResponseCache.class);
            assertThat(ctx).hasSingleBean(RedisResponseCache.class);
        });
    }
}
```

- [ ] **Step 2: 跑测试确认通过**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test -Dtest=CacheStoreWiringTest
```

Expected: PASS,3 tests。(条件注解 Task 1/3 已就位,此测试应直接绿;若红,说明注解写错,修注解而不是改测试。)

- [ ] **Step 3: 跑全量测试**

```bash
cd C:/practice/llm-gateway && JAVA_HOME=~/.jdks/ms-21.0.10 mvn test
```

Expected: `BUILD SUCCESS`,146 + 12 = 158 tests,0 failures。

- [ ] **Step 4: Commit**

```bash
cd C:/practice && git add llm-gateway/src/test/java/com/llm/gateway/cache/CacheStoreWiringTest.java
git commit -m "test: gateway.cache.store 条件装配回归测试"
```

---

### Task 5: Docker 编排与文档

**Files:**
- Modify: `llm-gateway/docker-compose.yml`
- Modify: `README.md`(仓库根)

- [ ] **Step 1: docker-compose.yml 加 redis 服务**

文件头注释「MySQL + 网关 + 管理台」改为「MySQL + Redis + 网关 + 管理台」。在 `mysql:` 服务之后插入:

```yaml
  redis:
    image: redis:7-alpine
    # 纯缓存:不持久化(丢了就丢了),内存上限 + LRU 淘汰。
    # 刻意不映射宿主机端口:仅容器网络内可达(与 gateway 9090 同一策略),故不设密码
    command: ["redis-server", "--save", "", "--appendonly", "no",
              "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 12
    restart: unless-stopped
```

- [ ] **Step 2: gateway 服务接入 redis**

`gateway.depends_on` 加:

```yaml
      redis:
        condition: service_healthy
```

`gateway.environment` 加(放 `GATEWAY_JWT_SECRET` 之前):

```yaml
      GATEWAY_CACHE_STORE: redis
      REDIS_HOST: redis
      REDIS_PORT: "6379"
```

- [ ] **Step 3: docker compose config 校验语法**

```bash
cd C:/practice/llm-gateway && docker compose --env-file /dev/null config --quiet 2>&1 | head -5
```

Expected: 只报 .env 必填变量缺失类 warning/error 属正常(本地无 .env);不得有 YAML 语法错误。若本地已有 .env 则直接 `docker compose config --quiet`,期望无输出。

- [ ] **Step 4: 根 README 缓存 bullet 更新**

`README.md` 核心能力中缓存一行:

```markdown
- **缓存**:精确匹配缓存,后端可切换(内存单机 / Redis 跨重启多实例共享,compose 默认 Redis;语义缓存预留),命中直接回放(含 SSE 回放)
```

- [ ] **Step 5: Commit**

```bash
cd C:/practice && git add llm-gateway/docker-compose.yml README.md
git commit -m "feat: compose 加 redis 服务,生产默认 Redis 缓存后端"
```

---

### Task 6: 实机冒烟(compose 全链路)

**Files:** 无代码改动;验证 spec §7 的四条验收。

前置:Docker Desktop 运行中;`cp .env.example .env` 并填 `MYSQL_ROOT_PASSWORD`、`GATEWAY_JWT_SECRET`(≥32 字符)、`ADMIN_USERNAME/PASSWORD`。冒烟结束后删除 `.env`(既有约定)。

- [ ] **Step 1: 起服务,确认四容器健康**

```bash
cd C:/practice/llm-gateway && docker compose up -d --build && sleep 30 && docker compose ps
```

Expected: mysql、redis、gateway、ui 四服务 `Up (healthy)`。

- [ ] **Step 2: 同一请求打两次,验证 cache_hit 且 Redis 有键**

```bash
for i in 1 2; do curl --noproxy '*' -s http://localhost:8081/v1/chat/completions \
  -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" \
  -d '{"model":"cheap","messages":[{"role":"user","content":"redis smoke test"}]}' | head -c 200; echo; done
docker compose exec redis redis-cli --scan --pattern 'gw:cache:*'
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" llm_gateway -e "SELECT id, model, status FROM request_log ORDER BY id DESC LIMIT 2;"'
```

Expected: 两次都返回 200 响应体;redis-cli 扫出一个 `gw:cache:exact:<sha256>` 键;request_log 最新两行,第二行 `status=cache_hit`。

- [ ] **Step 3: 重启 gateway,验证缓存跨重启仍命中**

```bash
docker compose restart gateway && sleep 25
curl --noproxy '*' -s http://localhost:8081/v1/chat/completions \
  -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" \
  -d '{"model":"cheap","messages":[{"role":"user","content":"redis smoke test"}]}' | head -c 200
docker compose exec mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" llm_gateway -e "SELECT id, status FROM request_log ORDER BY id DESC LIMIT 1;"'
```

Expected: 最新一行仍 `status=cache_hit` —— 内存缓存做不到,证明 Redis 化生效。(注意 TTL 300s,Step 2 到此步要在 5 分钟内完成,超了就换个 content 重跑 Step 2。)

- [ ] **Step 4: 停 redis,验证 fail-open**

```bash
docker compose stop redis
curl --noproxy '*' -s http://localhost:8081/v1/chat/completions \
  -H "Authorization: Bearer sk-demo-tenant-a" -H "Content-Type: application/json" \
  -d '{"model":"cheap","messages":[{"role":"user","content":"redis smoke test"}]}' | head -c 200; echo
docker compose logs gateway 2>&1 | grep -c "fail-open"
docker compose start redis
```

Expected: 请求正常 200(可能慢约 1s,等 Redis 超时);gateway 日志 grep 计数 ≥ 1(出现「Redis 缓存读取失败…fail-open」WARN)。

- [ ] **Step 5: 清理**

```bash
docker compose down && rm .env
```

- [ ] **Step 6: 冒烟结论记录**

无代码 commit;把四条验收结果(通过/问题)回报给用户,如有问题回到对应 Task 修复。

---

## Self-Review 记录

- **Spec 覆盖**:§2 架构(Task 1/3/4)、§3 信封(Task 2)、§4 fail-open + health(Task 1 Step 4 / Task 3)、§5 编排(Task 5)、§6 配置表(Task 1)、§7 测试冒烟(各 Task + Task 6)、§8 排除项无对应任务(正确)。一处有意偏离:spec §3 说用 `Usage.of(...)` 重建,实现改用 canonical 构造器 `new Usage(...)` —— 因 `Usage.of` 会重算 total,违反「上游 total 原样保留」契约,信封测试第 2 条专门锁定此行为。
- **占位符**:无 TBD/TODO;所有代码步骤含完整代码。
- **类型一致性**:`CachedResponse.of/toResponse`、`KEY_PREFIX`、`Cache(boolean, String, long, Semantic)` 各任务间已核对一致;`Fixtures.properties()` 默认 TTL 300s 与 RedisResponseCacheTest 的 `Duration.ofSeconds(300)` 匹配。
