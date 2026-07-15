# Nacos + Sentinel 接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 网关接入 Spring Cloud Alibaba——Nacos 承担配置中心与服务注册,Sentinel 替代自研令牌桶做每租户限流,规则持久化在 Nacos,全套在 Docker Compose 本地运行。

**Architecture:** 保持 Boot 4.1.0,引 SC 2025.1.2 + SCA 2025.1.0.0 两个 BOM。限流走现有 `RateLimiter` 接口的接缝:新增 `SentinelRateLimiter`(热点参数限流,租户为参数),与现有 `TokenBucketRateLimiter` 通过 `gateway.rate-limit.store` 开关互斥。运营参数经 `spring.config.import: optional:nacos:` 动态下发。

**Tech Stack:** Spring Boot 4.1.0 / Spring Cloud 2025.1.2 / Spring Cloud Alibaba 2025.1.0.0 / Sentinel + sentinel-datasource-nacos / Nacos Server 3.x (standalone) / Docker Compose

**设计文档:** `docs/superpowers/specs/2026-07-14-nacos-sentinel-design.md`

---

### Task 1: pom 引入 BOM 与依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: properties 增加版本号**

在 `<properties>` 中(现有 `java.version` 之后)加:

```xml
<spring-cloud.version>2025.1.2</spring-cloud.version>
<spring-cloud-alibaba.version>2025.1.0.0</spring-cloud-alibaba.version>
```

- [ ] **Step 2: 增加 dependencyManagement**

在 `</dependencies>` 之后、`<build>` 之前加:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${spring-cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

- [ ] **Step 3: 增加四个依赖**

在 `<dependencies>` 内(jtokkit 之后)加:

```xml
<!-- Nacos 配置中心:运营参数(限流/配额/熔断/敏感词)动态下发,不重启生效 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
<!-- Nacos 服务注册:实例健康状态可见,为多实例做准备 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<!-- Sentinel 限流:热点参数规则实现每租户 QPS -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
<!-- Sentinel 规则持久化到 Nacos:控制台改规则重启不丢 -->
<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-datasource-nacos</artifactId>
</dependency>
```

- [ ] **Step 4: 验证依赖可解析且编译通过**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS(若 sentinel-datasource-nacos 未被 BOM 管理版本,报 missing version——此时显式加 `<version>1.8.9</version>` 与 SCA 传递的 sentinel-core 版本对齐,以 `./mvnw dependency:tree -Dincludes=com.alibaba.csp` 输出为准)

- [ ] **Step 5: Commit**

```bash
git add pom.xml
git commit -m "build: 引入 Spring Cloud 2025.1.2 + SCA 2025.1.0.0,Nacos/Sentinel 依赖"
```

---

### Task 2: application.yaml 接入 Nacos 与 Sentinel 配置

**Files:**
- Modify: `src/main/resources/application.yaml`(spring 段与 gateway.rate-limit 段)
- Modify: `src/main/resources/application-prod.yaml`(如有 spring 段则同步检查,预期不用改)

- [ ] **Step 1: spring 段增加 Nacos/Sentinel 配置**

在 `application.yaml` 的 `spring:` 下(确认已有 `application.name`,没有则加)增加:

```yaml
spring:
  application:
    name: llm-gateway
  config:
    # optional: Nacos 不可达时用本地配置兜底启动,不失败
    import: "optional:nacos:llm-gateway.yaml"
  cloud:
    nacos:
      server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
      config:
        # 兜底注册中心地址同上;文件扩展名决定解析器
        file-extension: yaml
      discovery:
        # 本地无 Nacos 时禁止注册报错刷屏:由环境变量整体开关
        enabled: ${NACOS_DISCOVERY_ENABLED:true}
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:localhost:8858}
      # 首次请求才初始化会丢第一批指标,eager 提前建立心跳
      eager: true
      datasource:
        param-flow:
          nacos:
            server-addr: ${NACOS_SERVER_ADDR:localhost:8848}
            data-id: llm-gateway-param-flow-rules
            group-id: DEFAULT_GROUP
            data-type: json
            rule-type: param-flow
```

注意:`spring.config.import` 若 Nacos 未启动,`optional:` 前缀保证启动继续,日志仅 WARN。

- [ ] **Step 2: gateway.rate-limit 增加 store 开关**

```yaml
  # ---- 限流 ----
  rate-limit:
    # memory: 单机令牌桶(本地开发,无 Nacos 依赖)/ sentinel: 热点参数限流(规则在 Nacos)
    store: ${GATEWAY_RATE_LIMIT_STORE:memory}
    requests-per-minute: 60
```

- [ ] **Step 3: GatewayProperties.RateLimit 增加 store 字段**

`src/main/java/com/llm/gateway/config/GatewayProperties.java` 中:

```java
/**
 * 限流配置。
 *
 * @param store             限流实现:memory(单机令牌桶)/ sentinel(热点参数限流)
 * @param requestsPerMinute 每租户每分钟允许的请求数(memory 实现使用;sentinel 阈值在规则里)
 */
public record RateLimit(String store, int requestsPerMinute) {
}
```

- [ ] **Step 4: 编译 + 既有测试全过**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS,全部既有测试通过(record 增加字段是构造器绑定,yaml 已提供 store 值)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yaml src/main/java/com/llm/gateway/config/GatewayProperties.java
git commit -m "feat: application.yaml 接入 Nacos 配置导入/注册与 Sentinel 数据源"
```

---

### Task 3: SentinelRateLimiter(TDD)

**Files:**
- Create: `src/main/java/com/llm/gateway/ratelimit/SentinelRateLimiter.java`
- Modify: `src/main/java/com/llm/gateway/ratelimit/TokenBucketRateLimiter.java`(加条件注解)
- Test: `src/test/java/com/llm/gateway/ratelimit/SentinelRateLimiterTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.llm.gateway.exception.RateLimitExceededException;

/**
 * SentinelRateLimiter:内存中注册热点参数规则,验证超阈值抛 RateLimitExceededException。
 */
class SentinelRateLimiterTest {

    private final SentinelRateLimiter limiter = new SentinelRateLimiter();

    @AfterEach
    void cleanRules() {
        ParamFlowRuleManager.loadRules(List.of());
    }

    @Test
    void withinThresholdPasses() {
        loadRule(1000);
        assertThatCode(() -> limiter.acquire("tenant-a")).doesNotThrowAnyException();
    }

    @Test
    void exceedingThresholdThrows429Exception() {
        loadRule(1);
        limiter.acquire("tenant-b");
        assertThatThrownBy(() -> limiter.acquire("tenant-b"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void tenantsAreIsolated() {
        loadRule(1);
        limiter.acquire("tenant-c");
        assertThatCode(() -> limiter.acquire("tenant-d")).doesNotThrowAnyException();
    }

    private void loadRule(double qps) {
        ParamFlowRule rule = new ParamFlowRule(SentinelRateLimiter.RESOURCE)
                .setParamIdx(0)
                .setCount(qps);
        ParamFlowRuleManager.loadRules(List.of(rule));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -q test -Dtest=SentinelRateLimiterTest`
Expected: 编译失败,`SentinelRateLimiter` 不存在

- [ ] **Step 3: 实现 SentinelRateLimiter**

```java
package com.llm.gateway.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.llm.gateway.exception.RateLimitExceededException;

/**
 * 基于 Sentinel 热点参数规则的限流器:资源固定为 {@link #RESOURCE},租户作为参数索引 0。
 *
 * <p>阈值不在代码里:规则由 Nacos 数据源(dataId {@code llm-gateway-param-flow-rules})
 * 动态下发,改规则即时生效、重启不丢。超限统一转 {@link RateLimitExceededException},
 * 走既有 429 响应链。
 */
@Component
@ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "sentinel")
public class SentinelRateLimiter implements RateLimiter {

    /** Sentinel 资源名,规则配置与此对应。 */
    public static final String RESOURCE = "chat-completion";

    @Override
    public void acquire(String tenant) {
        try (Entry ignored = SphU.entry(RESOURCE, EntryType.IN, 1, tenant)) {
            // 进入即放行;try-with-resources 保证 exit,统计窗口正确
        } catch (BlockException e) {
            throw new RateLimitExceededException(
                    "租户 [" + tenant + "] 请求过于频繁,请稍后重试");
        }
    }
}
```

- [ ] **Step 4: TokenBucketRateLimiter 加互斥条件**

类上增加(`@Component` 处):

```java
@Component
@ConditionalOnProperty(name = "gateway.rate-limit.store", havingValue = "memory", matchIfMissing = true)
public class TokenBucketRateLimiter implements RateLimiter {
```

并加 import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`。

- [ ] **Step 5: 全量测试通过**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS(新测试 3 个通过;既有上下文测试不受影响,默认 store=memory)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/llm/gateway/ratelimit/ src/test/java/com/llm/gateway/ratelimit/SentinelRateLimiterTest.java
git commit -m "feat: Sentinel 热点参数限流器,store 开关与令牌桶互斥"
```

---

### Task 4: Compose 增加 Nacos、Sentinel Dashboard 与规则初始化

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`
- Create: `deploy/nacos-init/init.sh`(首启发布 Nacos 配置与限流规则)

- [ ] **Step 1: compose 增加 nacos 服务**

在 `redis:` 服务之后加:

```yaml
  nacos:
    image: nacos/nacos-server:v3.1.1
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLED: "false"
      TZ: Asia/Shanghai
    ports:
      - "8848:8848"   # 控制台/HTTP API
    volumes:
      - nacos-data:/home/nacos/data
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:8848/nacos/v3/client/health/readiness || exit 1"]
      interval: 5s
      timeout: 3s
      retries: 60
      start_period: 20s
    restart: unless-stopped

  # 首启把运营参数与限流规则发布到 Nacos(幂等:已存在则覆盖为相同内容)
  nacos-init:
    image: curlimages/curl:8.10.1
    depends_on:
      nacos:
        condition: service_healthy
    volumes:
      - ./deploy/nacos-init/init.sh:/init.sh:ro
    entrypoint: ["sh", "/init.sh"]
    restart: "no"

  sentinel-dashboard:
    image: bladex/sentinel-dashboard:latest
    ports:
      - "8858:8858"
    restart: unless-stopped
```

`volumes:` 段追加 `nacos-data:`。

- [ ] **Step 2: gateway 服务接线**

`gateway.environment` 增加:

```yaml
      NACOS_SERVER_ADDR: nacos:8848
      SENTINEL_DASHBOARD: sentinel-dashboard:8858
      GATEWAY_RATE_LIMIT_STORE: sentinel
```

`gateway.depends_on` 增加:

```yaml
      nacos:
        condition: service_healthy
      nacos-init:
        condition: service_completed_successfully
```

- [ ] **Step 3: 编写 init.sh**

```sh
#!/bin/sh
# 首启向 Nacos 发布:网关运营参数 + Sentinel 热点参数限流规则。
# Nacos v3 兼容 v1 配置发布 API;发布是覆盖语义,重复执行幂等。
set -eu
NACOS="http://nacos:8848/nacos/v1/cs/configs"

# 运营参数:与 application.yaml 中 gateway.* 同结构,Nacos 侧优先级更高
curl -fsS -X POST "$NACOS" \
  --data-urlencode "dataId=llm-gateway.yaml" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=gateway:
  rate-limit:
    requests-per-minute: 60
  quota:
    tokens-per-tenant: 1000000
  resilience:
    max-retries: 2
    circuit-breaker:
      failure-threshold: 5
      open-seconds: 30
"

# Sentinel 热点参数规则:chat-completion 资源,参数 0(租户),单租户 5 QPS
curl -fsS -X POST "$NACOS" \
  --data-urlencode "dataId=llm-gateway-param-flow-rules" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=json" \
  --data-urlencode 'content=[
  {
    "resource": "chat-completion",
    "paramIdx": 0,
    "grade": 1,
    "count": 5,
    "durationInSec": 1
  }
]'
echo "nacos-init done"
```

- [ ] **Step 4: .env.example 补充说明**

追加:

```
# ===== Nacos / Sentinel(compose 内网自动接线,以下仅本机裸跑网关时用)=====
# NACOS_SERVER_ADDR=localhost:8848
# SENTINEL_DASHBOARD=localhost:8858
# GATEWAY_RATE_LIMIT_STORE=sentinel
```

- [ ] **Step 5: compose 语法校验**

Run: `docker compose config -q`
Expected: 无输出(校验通过)

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml .env.example deploy/nacos-init/init.sh
git commit -m "feat: compose 增加 Nacos/Sentinel Dashboard,首启发布配置与限流规则"
```

---

### Task 5: 端到端部署验证

**Files:** 无代码改动,验证 + 可能的修复

- [ ] **Step 1: 全量重建启动**

Run: `cd C:/practice/llm-gateway && docker compose up -d --build`
Expected: 六个服务全部 Up/healthy,nacos-init Exited(0)

- [ ] **Step 2: 验证 Nacos**

Run: `curl -s --noproxy '*' "http://localhost:8848/nacos/v1/ns/instance/list?serviceName=llm-gateway" `
Expected: JSON 中 hosts 含一个 healthy 实例(gateway 容器 IP:8080)

- [ ] **Step 3: 验证限流生效(429)**

用管理台创建的 API Key 连发请求(规则 5 QPS):

```bash
for i in $(seq 1 15); do
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}\n' \
    -X POST http://localhost:8081/v1/chat/completions \
    -H "Authorization: Bearer <API_KEY>" -H 'Content-Type: application/json' \
    -d '{"model":"default","messages":[{"role":"user","content":"hi"}]}' &
done; wait
```

Expected: 部分 200/4xx 业务码 + 若干 **429**

- [ ] **Step 4: 验证动态调整**

Nacos 控制台(localhost:8848)把 `llm-gateway-param-flow-rules` 的 count 改为 1,重复 Step 3。
Expected: 429 比例显著上升,网关未重启

- [ ] **Step 5: Commit(如有修复)并更新设计文档状态**

```bash
git add -A && git commit -m "fix: 部署验证问题修复(如有)"
```

---

## Self-Review 结论

- 规格覆盖:依赖/配置中心/服务发现/Sentinel 限流/compose/验收 → Task 1-5 一一对应。
- 已知风险点已写入步骤:sentinel-datasource-nacos 版本(T1S4)、Nacos v3 镜像 API 路径(T4)、SCA 对 Boot 4.1 未官方声明(回退方案在设计文档)。
- 类型一致:资源名 `chat-completion` 在 T3 代码与 T4 规则 JSON 一致;`store` 字段在 T2/T3 一致。
