# llm-gateway 子项目 5:生产运维硬化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 llm-gateway 前后端从「本地可跑」推进到「单机生产可部署」:Actuator 收口、CORS 收敛、traceId 日志、优雅停机、Docker Compose 交付,并清掉安全基线与 Token 计数的遗留 backlog。

**Architecture:** 保持 MVC servlet 栈 + 手写 Filter 体系(不引入 WebFlux / Spring Security 过滤链,与 SCA 终态兼容)。Actuator 用管理端口分离(9090,不映射宿主机);CORS 从 MVC 级改为 Servlet 级 CorsFilter(鉴权前);部署拓扑 = 前端 nginx 镜像同源反代 `/admin`、`/v1` 到后端(方案 A)。

**Tech Stack:** Spring Boot 4.1 / Java 21 / Jackson 3(`tools.jackson.*`)/ MyBatis-Plus 3.5.16 / jjwt 0.12;前端 Vue 3 + Element Plus + Vite 5;交付 Docker Compose(mysql:8 + temurin-21 + nginx:alpine)。

**Spec:** `C:\practice\docs\superpowers\specs\2026-07-11-llm-gateway-ops-hardening-design.md`

---

## 环境须知(每个任务通用)

- 两个项目**均非 git 仓库**(用户既有惯例),所有「commit」步骤替换为「运行验证命令通过」作为任务完成门槛。
- 后端构建:**mvnw 是坏的**,用系统 Maven + 指定 JDK。所有 mvn 命令在 `C:\practice\llm-gateway` 下执行,形如:
  ```bash
  cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=XxxTest
  ```
- 集成测试(@SpringBootTest)连**本地 MySQL**(root/123456,库 llm_gateway,schema 已初始化)。跑测试前确保 MySQL 在运行。
- 本机 curl 一律加 `--noproxy '*'`。
- 冒烟起服务前先确认 8080 无旧进程残留(曾因旧服务占端口误判新代码未生效):`netstat -ano | grep -E ':(8080|9090)'`。
- 前端命令在 `C:\practice\llm-gateway-ui` 下执行(`npm run build` 作为验证)。
- 现状基线:后端 129 个测试全绿。

---

### Task 1: Prometheus 依赖 + 管理端口分离 + 优雅停机(纯配置)

**Files:**
- Modify: `llm-gateway/pom.xml`(actuator 依赖之后,约 48 行)
- Modify: `llm-gateway/src/main/resources/application.yaml`

- [ ] **Step 1: pom.xml 补 micrometer-registry-prometheus**

在 `spring-boot-starter-actuator` 依赖块(pom.xml:45-48)之后插入:

```xml
        <!-- Prometheus 指标输出:/actuator/prometheus 需要该 registry,缺失则端点 404 -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: application.yaml 加 server/lifecycle/management 配置**

(a) 在文件顶部 `spring:` 块的 `threads:` 之前(或之后,同级缩进)加入 `lifecycle`;(b) 新增顶层 `server:` 段;(c) 替换现有 `management:` 段。

在 `spring:` 下加(与 `threads:` 同级):

```yaml
  # 优雅停机第二段:给进行中的请求(含 SSE 流)最多 30s 收尾
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

在 `spring:` 块结束后新增顶层段(放在 `mybatis-plus:` 之前):

```yaml
# 优雅停机:SIGTERM 后不再接受新请求,等待进行中的请求完成
server:
  shutdown: graceful
```

把现有 `management:` 段(application.yaml:36-40)整体替换为:

```yaml
# Actuator:管理端口分离(主端口 8080 上 /actuator/** 不存在);
# 9090 仅容器网络/内网使用(healthcheck、Prometheus 抓取),部署时不映射宿主机
management:
  server:
    port: ${GATEWAY_MANAGEMENT_PORT:9090}
  endpoint:
    health:
      show-details: always
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
```

- [ ] **Step 3: 验证 —— 上下文可启动、全量测试不回归**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=LlmGatewayApplicationTests
```
Expected: BUILD SUCCESS(@SpringBootTest 为 MOCK 环境,management 端口不影响测试)。

---

### Task 2: CORS 收敛 + 401 CORS 头修复(CorsFilter 前置)

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/config/CorsFilterTest.java`(新建)
- Create: `llm-gateway/src/main/java/com/llm/gateway/config/CorsConfig.java`
- Delete: `llm-gateway/src/main/java/com/llm/gateway/config/WebCorsConfig.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/AuthFilterConfig.java`(顺序重排)
- Modify: `llm-gateway/src/main/resources/application.yaml`(gateway.admin 加 allowed-origins)

- [ ] **Step 1: 写失败测试**

新建 `CorsFilterTest.java`:

```java
package com.llm.gateway.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS 收敛与 401 CORS 头:CorsFilter 注册在鉴权过滤器之前,
 * 未登录 401(过滤器直写响应)也必须带 Access-Control-Allow-Origin,预检不需要登录。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.allowed-origins=http://localhost:5173"
})
@AutoConfigureMockMvc
class CorsFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthorized401CarriesCorsHeaders() throws Exception {
        mockMvc.perform(get("/admin/api-keys").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void preflightHandledBeforeAuth() throws Exception {
        mockMvc.perform(options("/admin/api-keys")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void disallowedOriginRejected() throws Exception {
        mockMvc.perform(get("/admin/api-keys").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=CorsFilterTest
```
Expected: FAIL —— `unauthorized401CarriesCorsHeaders` 缺 `Access-Control-Allow-Origin` 头(现 MVC 级 CORS 走不到 Filter 直写的 401);`disallowedOriginRejected` 现状 401 而非 403。

- [ ] **Step 3: application.yaml 的 gateway.admin 段加 allowed-origins**

把 `gateway.admin` 段(application.yaml 末尾)改为:

```yaml
  # ---- 管理端鉴权 ----
  admin:
    jwt-secret: ${GATEWAY_JWT_SECRET:}
    token-ttl-minutes: 120
    bootstrap-username: ${ADMIN_USERNAME:}
    bootstrap-password: ${ADMIN_PASSWORD:}
    # 管理端跨域白名单(逗号分隔)。开发默认放行 Vite dev server;
    # 生产(prod profile)默认收敛为空 —— nginx 同源反代下浏览器不发跨域请求
    allowed-origins: ${GATEWAY_ADMIN_ALLOWED_ORIGINS:http://localhost:5173}
```

- [ ] **Step 4: 新建 CorsConfig,删除 WebCorsConfig**

新建 `CorsConfig.java`:

```java
package com.llm.gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 管理端 CORS:用 Servlet 层 {@link CorsFilter}(注册在鉴权过滤器之前)而非 MVC 级配置,
 * 使鉴权过滤器直写的 401 响应也带 CORS 头;预检 OPTIONS 亦在此处理。
 *
 * <p>白名单来自 {@code gateway.admin.allowed-origins}(逗号分隔):开发默认放行 Vite dev server;
 * 生产 prod profile 默认为空(nginx 同源反代,浏览器不发跨域请求),分域部署时用
 * {@code GATEWAY_ADMIN_ALLOWED_ORIGINS} 打开。为空时不注册任何 CORS 映射。
 * {@code /v1/**} 是服务端对服务端 API,不配 CORS。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> adminCorsFilter(
            @Value("${gateway.admin.allowed-origins:}") List<String> allowedOrigins) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOriginPatterns(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            source.registerCorsConfiguration("/admin/**", config);
        }
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns("/admin/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("adminCorsFilter");
        return registration;
    }
}
```

删除 `WebCorsConfig.java` 整个文件:

```bash
rm /c/practice/llm-gateway/src/main/java/com/llm/gateway/config/WebCorsConfig.java
```

- [ ] **Step 5: AuthFilterConfig 顺序重排**

修改 `AuthFilterConfig.java` 三处 `setOrder`,并更新类 javadoc。类 javadoc 第一段之后补一行说明:

```java
 * <p>全局 Filter 顺序:TraceIdFilter(HIGHEST)→ CorsFilter(+10)→ ApiKey(+20)→ AdminJwt(+30)→ 审计(+40)。
```

三处顺序改为:

```java
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);   // apiKeyAuthFilter(原 HIGHEST_PRECEDENCE)
```
```java
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);   // adminJwtFilter(原 +10)
```
```java
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 40);   // adminAuditFilter(原 +20)
```

(行内注释不必保留,以上仅标注改动位置;HIGHEST_PRECEDENCE 与 +10 留给 Task 3 的 TraceIdFilter 与本任务的 CorsFilter。)

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest='CorsFilterTest,AdminJwtFilterTest,AdminAuditFilterTest'
```
Expected: PASS(3 个测试类全绿;鉴权与审计行为不受顺序重排影响)。

---

### Task 3: TraceIdFilter + MDC 贯穿 + GatewayService 复用 traceId

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/observability/TraceIdFilterTest.java`(新建)
- Create: `llm-gateway/src/main/java/com/llm/gateway/observability/TraceIdFilter.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/observability/ObservabilityFilterConfig.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/core/GatewayService.java:94-96,145-148`

- [ ] **Step 1: 写失败测试**

新建 `TraceIdFilterTest.java`:

```java
package com.llm.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * TraceIdFilter:MDC 写入与 finally 清理、响应头回写、外部合法值透传、非法值替换。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void generatesTraceIdWritesMdcAndHeaderThenCleansUp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isNotBlank().hasSize(16);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(inChain.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull(); // finally 已清理
    }

    @Test
    void propagatesValidIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.addHeader(TraceIdFilter.HEADER, "client-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isEqualTo("client-abc_123");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("client-abc_123");
    }

    @Test
    void replacesIllegalIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.addHeader(TraceIdFilter.HEADER, "bad id<script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isNotEqualTo("bad id<script>").hasSize(16);
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=TraceIdFilterTest
```
Expected: COMPILATION ERROR(TraceIdFilter 不存在)。

- [ ] **Step 3: 实现 TraceIdFilter 与注册配置**

新建 `TraceIdFilter.java`:

```java
package com.llm.gateway.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 链路追踪过滤器(所有 Filter 之前):把 traceId 放入 MDC 供日志 pattern 输出,并回写响应头。
 *
 * <p>优先复用请求头 {@code X-Request-Id}(限长 64、仅字母数字下划线连字符,防日志注入),
 * 缺失或非法时自生成。{@code GatewayService} 用同一 traceId 作为 request_log.request_id,
 * 应用日志、响应头、审计表三者可互查。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key,与 logback pattern 的 %X{traceId:-} 对应。 */
    public static final String MDC_KEY = "traceId";
    /** 透传/回写的请求头名。 */
    public static final String HEADER = "X-Request-Id";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && SAFE.matcher(incoming).matches() ? incoming : newTraceId();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** @return 16 位短 ID(UUID 去横线取前 16 位) */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
```

新建 `ObservabilityFilterConfig.java`:

```java
package com.llm.gateway.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性过滤器注册:TraceIdFilter 排在最前(CORS 与鉴权过滤器之前),
 * 保证过滤器直写的 401/403 响应对应的日志也带 traceId。
 */
@Configuration
public class ObservabilityFilterConfig {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
```

- [ ] **Step 4: GatewayService 复用 traceId 作为 requestId**

修改 `GatewayService.java`:

(a) import 区:删除 `import java.util.UUID;`,加入:

```java
import org.slf4j.MDC;
import com.llm.gateway.observability.TraceIdFilter;
```

(b) `complete(...)` 中(约 95-96 行)与 `completeStream(...)` 中(约 147-148 行),把

```java
        GatewayContext context =
                new GatewayContext(UUID.randomUUID().toString(), principal.tenant(), request.model(), System.nanoTime());
```

两处均替换为:

```java
        GatewayContext context =
                new GatewayContext(newRequestId(), principal.tenant(), request.model(), System.nanoTime());
```

(c) 类尾部(persistError 之后)加私有方法:

```java
    /** 复用 TraceIdFilter 写入 MDC 的 traceId 作为请求 ID(应用日志/响应头/request_log 同 ID);无过滤器场景(单测)自生成。 */
    private static String newRequestId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId != null ? traceId : TraceIdFilter.newTraceId();
    }
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest='TraceIdFilterTest,ChatCompletionStreamIntegrationTest'
```
Expected: PASS(流式集成测试不回归,request_id 变为 16 位短 ID 不影响断言)。

---

### Task 4: logback-spring.xml + application-prod.yaml

**Files:**
- Create: `llm-gateway/src/main/resources/logback-spring.xml`
- Create: `llm-gateway/src/main/resources/application-prod.yaml`

- [ ] **Step 1: 新建 logback-spring.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- 日志硬化:控制台始终输出(docker logs / 开发);prod profile 追加按天+大小滚动的文件日志。
     pattern 中 %X{traceId:-} 来自 TraceIdFilter 写入的 MDC。 -->
<configuration>
    <property name="LOG_PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-}] %logger{36} - %msg%n"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${LOG_PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>

    <!-- 开发/测试(未激活 prod):应用包打开 DEBUG -->
    <springProfile name="!prod">
        <logger name="com.llm.gateway" level="DEBUG"/>
    </springProfile>

    <!-- 生产:滚动文件,按天 + 单文件 100MB,保留 14 天,总量上限 2GB -->
    <springProfile name="prod">
        <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/gateway.log</file>
            <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                <fileNamePattern>logs/gateway.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
                <maxFileSize>100MB</maxFileSize>
                <maxHistory>14</maxHistory>
                <totalSizeCap>2GB</totalSizeCap>
            </rollingPolicy>
            <encoder>
                <pattern>${LOG_PATTERN}</pattern>
                <charset>UTF-8</charset>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="FILE"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 2: 新建 application-prod.yaml**

```yaml
# 生产 profile 差异(SPRING_PROFILES_ACTIVE=prod 激活;文件日志见 logback-spring.xml 的 prod springProfile)
gateway:
  admin:
    # 同源反代部署默认零跨域;确需分域时用 GATEWAY_ADMIN_ALLOWED_ORIGINS 打开(逗号分隔)
    allowed-origins: ${GATEWAY_ADMIN_ALLOWED_ORIGINS:}
```

- [ ] **Step 3: 验证 —— logback 配置可解析、上下文可启动**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=LlmGatewayApplicationTests
```
Expected: BUILD SUCCESS,且控制台日志行变为新 pattern(形如 `2026-07-11 10:00:00.000 INFO  [main] [] com.llm...`,traceId 位置在测试里为空)。若 XML 有错,Logback 启动即报 `Logback configuration error`。

---

### Task 5: 登录响应加 expiresAt(后端)

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/auth/admin/AdminAuthServiceTest.java:41-47`(修改)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminAuthService.java:82-107`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/auth/admin/AdminAuthController.java:27-48`

- [ ] **Step 1: 修改测试(先红)**

把 `AdminAuthServiceTest.loginIssuesVerifiableJwt`(42-47 行)替换为:

```java
    @Test
    void loginIssuesVerifiableJwt() {
        long before = System.currentTimeMillis();
        AdminAuthService.LoginResult result = service.login("admin", "secret-pass", "127.0.0.1");
        Optional<AdminPrincipal> principal = service.verify(result.token());
        assertThat(principal).isPresent();
        assertThat(principal.get().username()).isEqualTo("admin");
        // TTL 120 分钟:过期时刻应落在 [before+120min-1s, now+120min+1s]
        assertThat(result.expiresAtMillis())
                .isBetween(before + 120 * 60_000L - 1_000, System.currentTimeMillis() + 120 * 60_000L + 1_000);
    }
```

- [ ] **Step 2: 跑测试确认编译失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=AdminAuthServiceTest
```
Expected: COMPILATION ERROR(`LoginResult` 不存在)。

- [ ] **Step 3: AdminAuthService 增加 LoginResult 并改签发**

在 `AdminAuthService` 类内(LoginLockedException 定义之后)加:

```java
    /** 登录结果:JWT 与过期时刻(epoch 毫秒,与 token 的 exp 一致)。 */
    public record LoginResult(String token, long expiresAtMillis) {
    }
```

`login` 方法签名与 javadoc `@return` 改为返回 `LoginResult`,末尾签发部分(100-106 行)替换为:

```java
        Date expiry = new Date(now + properties.tokenTtlMinutes() * 60_000L);
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now))
                .expiration(expiry)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new LoginResult(token, expiry.getTime());
```

- [ ] **Step 4: AdminAuthController 响应体加 expiresAt**

`LoginResponse` 与 `login` 方法(27-48 行)替换为:

```java
    /** 登录响应:JWT、用户名、过期时刻(epoch 毫秒,前端据此在过期后直接跳登录)。 */
    public record LoginResponse(String token, String username, long expiresAt) {
    }
```

```java
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        AdminAuthService.LoginResult result =
                authService.login(body.username(), body.password(), request.getRemoteAddr());
        return R.ok(new LoginResponse(result.token(), body.username(), result.expiresAtMillis()));
    }
```

(javadoc `@return` 同步改为「JWT、用户名与过期时刻」。)

- [ ] **Step 5: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest='AdminAuthServiceTest,AdminJwtFilterTest'
```
Expected: PASS(`lockedAfterFiveFailures` 等只断言异常,不受返回类型影响)。

---

### Task 6: 审计/请求日志时间范围筛选(后端)

**Files:**
- Create: `llm-gateway/src/test/java/com/llm/gateway/AdminTestTokens.java`(测试夹具)
- Test: `llm-gateway/src/test/java/com/llm/gateway/admin/AdminTimeFilterIntegrationTest.java`(新建)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/audit/AuditAdminController.java:38-50`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/admin/LogAdminController.java:42-62`

- [ ] **Step 1: 新建测试夹具 AdminTestTokens**

```java
package com.llm.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 集成测试公共夹具:用测试密钥直接签发管理端 JWT(过滤器只验签不查库)。
 * 使用方须以 {@code "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET} 覆盖配置。
 */
public final class AdminTestTokens {

    public static final String TEST_SECRET = "test-secret-0123456789abcdef0123456789abcdef";

    private AdminTestTokens() {
    }

    /** @return 有效期 60s 的合法管理端 JWT */
    public static String issue() {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject("it-admin")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
```

- [ ] **Step 2: 写失败测试**

新建 `AdminTimeFilterIntegrationTest.java`:

```java
package com.llm.gateway.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

/**
 * 审计日志与请求日志的时间范围筛选(from/to,ISO-8601 日期时间,对 created_at 闭区间)。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminTimeFilterIntegrationTest {

    private static final String MARK = "it-timefilter";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() {
        jdbcTemplate.update("""
                INSERT INTO admin_audit_log (username, action, resource, status, created_at)
                VALUES (?, 'LOGIN_OK', 'auth/login', 200, '2026-01-01 10:00:00'),
                       (?, 'LOGIN_OK', 'auth/login', 200, '2026-06-01 10:00:00')
                """, MARK, MARK);
        jdbcTemplate.update("""
                INSERT INTO request_log (request_id, tenant, requested_model, status, created_at)
                VALUES ('it-tf-1', ?, 'mock-model', 'success', '2026-01-01 10:00:00'),
                       ('it-tf-2', ?, 'mock-model', 'success', '2026-06-01 10:00:00')
                """, MARK, MARK);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM admin_audit_log WHERE username = ?", MARK);
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", MARK);
    }

    @Test
    void auditLogsFilteredByRange() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK)
                        .param("from", "2026-05-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void auditLogsFromOnly() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK)
                        .param("from", "2026-05-01T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void auditLogsNoRangeReturnsAll() throws Exception {
        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("username", MARK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void requestLogsFilteredByRange() throws Exception {
        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("tenant", MARK)
                        .param("from", "2026-05-01T00:00:00")
                        .param("to", "2026-12-31T23:59:59"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void requestLogsToOnly() throws Exception {
        mockMvc.perform(get("/admin/logs")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .param("tenant", MARK)
                        .param("to", "2026-05-01T00:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=AdminTimeFilterIntegrationTest
```
Expected: FAIL —— 带 from/to 的用例返回 total=2(参数被忽略,未实现筛选)。

- [ ] **Step 4: AuditAdminController 加 from/to**

`list` 方法(38-50 行)替换为(新增 import:`java.time.LocalDateTime`、`org.springframework.format.annotation.DateTimeFormat`):

```java
    /**
     * 分页查询审计日志(时间倒序)。
     *
     * @param username 按用户名精确筛选,可空
     * @param action   按动作精确筛选,可空
     * @param from     创建时间下界(含,ISO-8601 如 2026-07-11T00:00:00),可空
     * @param to       创建时间上界(含),可空
     * @param page     页码(1 起)
     * @param size     每页大小
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<AdminAuditLogEntity>> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LambdaQueryWrapper<AdminAuditLogEntity> query = Wrappers.<AdminAuditLogEntity>lambdaQuery()
                .eq(username != null && !username.isBlank(), AdminAuditLogEntity::getUsername, username)
                .eq(action != null && !action.isBlank(), AdminAuditLogEntity::getAction, action)
                .ge(from != null, AdminAuditLogEntity::getCreatedAt, from)
                .le(to != null, AdminAuditLogEntity::getCreatedAt, to)
                .orderByDesc(AdminAuditLogEntity::getId);
        Page<AdminAuditLogEntity> p = mapper.selectPage(new Page<>(page, size), query);
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
```

- [ ] **Step 5: LogAdminController 加 from/to**

`list` 方法(42-62 行)替换为(新增同样两个 import):

```java
    /**
     * 分页查询请求日志。
     *
     * @param tenant 租户筛选(可空)
     * @param status 状态筛选(可空)
     * @param model  请求模型模糊筛选(可空)
     * @param from   创建时间下界(含,ISO-8601),可空
     * @param to     创建时间上界(含),可空
     * @param page   页码(从 1 起)
     * @param size   每页大小
     * @return 分页结果
     */
    @GetMapping
    public R<PageResult<RequestLogEntity>> list(@RequestParam(required = false) String tenant,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String model,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                @RequestParam(required = false)
                                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size) {
        QueryWrapper<RequestLogEntity> query = new QueryWrapper<>();
        if (StringUtils.hasText(tenant)) {
            query.eq("tenant", tenant);
        }
        if (StringUtils.hasText(status)) {
            query.eq("status", status);
        }
        if (StringUtils.hasText(model)) {
            query.like("requested_model", model);
        }
        if (from != null) {
            query.ge("created_at", from);
        }
        if (to != null) {
            query.le("created_at", to);
        }
        query.orderByDesc("id");

        Page<RequestLogEntity> p = mapper.selectPage(new Page<>(page, size), query);
        return R.ok(new PageResult<>(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize()));
    }
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=AdminTimeFilterIntegrationTest
```
Expected: PASS(5 个用例)。

---

### Task 7: Pricing PUT 全量更新(缓存单价可清 NULL)

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/admin/PricingNullUpdateIntegrationTest.java`(新建)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/admin/PricingAdminController.java:55-68`

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

/**
 * Pricing PUT 全量更新语义:请求体缺省(null)的缓存单价必须写回 NULL
 * (旧实现 updateById 跳过 null 字段,缓存单价一旦设置便无法清除)。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PricingNullUpdateIntegrationTest {

    private static final String MODEL = "it-nullable-model";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM model_pricing WHERE model = ?", MODEL);
    }

    @Test
    void putClearsCachePricesBackToNull() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO model_pricing (model, input_per_1k, output_per_1k, cache_read_per_1k, cache_write_per_1k)
                VALUES (?, 0.001, 0.002, 0.0005, 0.00125)
                """, MODEL);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM model_pricing WHERE model = ?", Long.class, MODEL);

        mockMvc.perform(put("/admin/pricing/" + id)
                        .header("Authorization", "Bearer " + AdminTestTokens.issue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"" + MODEL + "\",\"inputPer1k\":0.001,\"outputPer1k\":0.002}"))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT cache_read_per_1k, cache_write_per_1k FROM model_pricing WHERE id = ?", id);
        assertThat(row.get("cache_read_per_1k")).isNull();
        assertThat(row.get("cache_write_per_1k")).isNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=PricingNullUpdateIntegrationTest
```
Expected: FAIL —— `cache_read_per_1k` 仍是 0.0005(updateById 跳过了 null)。

- [ ] **Step 3: 改 update 为 UpdateWrapper 全列 set**

`PricingAdminController.update`(62-68 行)替换为:

```java
    /**
     * 修改(PUT 全量更新语义:显式 set 全部业务列,null 也写入 ——
     * updateById 会跳过 null 字段,缓存单价将无法清回 NULL)。
     *
     * @param id     主键
     * @param entity 新值
     * @return 修改后的实体
     */
    @PutMapping("/{id}")
    public R<ModelPricingEntity> update(@PathVariable Long id, @RequestBody ModelPricingEntity entity) {
        entity.setId(id);
        mapper.update(null, Wrappers.<ModelPricingEntity>update()
                .eq("id", id)
                .set("model", entity.getModel())
                .set("input_per_1k", entity.getInputPer1k())
                .set("output_per_1k", entity.getOutputPer1k())
                .set("cache_read_per_1k", entity.getCacheReadPer1k())
                .set("cache_write_per_1k", entity.getCacheWritePer1k()));
        refreshService.reloadAll();
        return R.ok(entity);
    }
```

(`Wrappers` 已在该文件 import。)

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest='PricingNullUpdateIntegrationTest,CostCalculatorTest,TokenBillingIntegrationTest'
```
Expected: PASS(计费相关测试不回归)。

---

### Task 8: stats 成本排除 cache_hit + 缓存命中计数

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/admin/LogStatsIntegrationTest.java`(新建)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/admin/LogAdminController.java`(stats/toStatRow/StatRow)

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.admin;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.llm.gateway.AdminTestTokens;

/**
 * 租户统计口径:cost=上游真实成本(缓存命中行不计),tokens=全量(配额口径),另计缓存命中次数。
 */
@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogStatsIntegrationTest {

    private static final String TENANT = "it-stats";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void seed() {
        jdbcTemplate.update("""
                INSERT INTO request_log
                    (request_id, tenant, requested_model, served_model, total_tokens, cost_usd, cache_hit, status)
                VALUES ('it-st-1', ?, 'mock-model', 'mock-model', 100, 0.5, 0, 'success'),
                       ('it-st-2', ?, 'mock-model', 'mock-model', 100, 0.3, 1, 'cache_hit')
                """, TENANT, TENANT);
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("DELETE FROM request_log WHERE tenant = ?", TENANT);
    }

    @Test
    void statsExcludeCacheHitCostAndCountCacheHits() throws Exception {
        mockMvc.perform(get("/admin/logs/stats")
                        .header("Authorization", "Bearer " + AdminTestTokens.issue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].requests", contains(2)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].tokens", contains(200)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].cost", contains(0.5)))
                .andExpect(jsonPath("$.data[?(@.tenant=='it-stats')].cacheHits", contains(1)));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=LogStatsIntegrationTest
```
Expected: FAIL —— cost 为 0.8(缓存命中行被计入),且无 cacheHits 字段。

- [ ] **Step 3: 实现新统计口径**

`LogAdminController` 中 `stats()`、`toStatRow(...)`、`StatRow` 替换为:

```java
    /**
     * 按租户聚合用量与成本统计。
     *
     * @return 每租户一行:请求数、总 Token(全量,配额口径)、上游成本(不含缓存命中行)、缓存命中次数
     */
    @GetMapping("/stats")
    public R<List<StatRow>> stats() {
        QueryWrapper<RequestLogEntity> query = new QueryWrapper<>();
        query.select("tenant",
                "COUNT(*) AS requests",
                "IFNULL(SUM(total_tokens), 0) AS tokens",
                // 成本=上游真实成本口径:缓存命中行没有上游调用,不计入
                "IFNULL(SUM(CASE WHEN cache_hit = 1 THEN 0 ELSE cost_usd END), 0) AS cost",
                "SUM(CASE WHEN cache_hit = 1 THEN 1 ELSE 0 END) AS cache_hits");
        query.groupBy("tenant");

        List<StatRow> rows = mapper.selectMaps(query).stream()
                .map(this::toStatRow)
                .toList();
        return R.ok(rows);
    }

    /**
     * 把聚合 Map 转成统计行。
     *
     * @param row 聚合结果
     * @return 统计行
     */
    private StatRow toStatRow(Map<String, Object> row) {
        String tenant = String.valueOf(row.get("tenant"));
        long requests = toNumber(row.get("requests")).longValue();
        long tokens = toNumber(row.get("tokens")).longValue();
        double cost = toNumber(row.get("cost")).doubleValue();
        long cacheHits = toNumber(row.get("cache_hits")).longValue();
        return new StatRow(tenant, requests, tokens, cost, cacheHits);
    }
```

```java
    /**
     * 租户统计行。
     *
     * @param tenant    租户
     * @param requests  请求数
     * @param tokens    总 Token(含缓存命中行,配额消耗口径)
     * @param cost      上游成本(美元,不含缓存命中行)
     * @param cacheHits 缓存命中次数
     */
    public record StatRow(String tenant, long requests, long tokens, double cost, long cacheHits) {
    }
```

(`toNumber` 保持不变。)

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=LogStatsIntegrationTest
```
Expected: PASS。

---

### Task 9: 缓存回放中断的审计修正

**Files:**
- Test: `llm-gateway/src/test/java/com/llm/gateway/core/GatewayServiceCacheReplayTest.java`(新建)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/core/GatewayContext.java:56-58`(加 getter)
- Modify: `llm-gateway/src/main/java/com/llm/gateway/core/GatewayService.java`(cache-hit 块、catch 块、persistPartial、新私有方法)

- [ ] **Step 1: 写失败测试**

```java
package com.llm.gateway.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.llm.gateway.api.dto.ChatCompletionRequest;
import com.llm.gateway.api.dto.ChatCompletionResponse;
import com.llm.gateway.api.dto.ChatMessage;
import com.llm.gateway.api.dto.Usage;
import com.llm.gateway.auth.ApiKeyService;
import com.llm.gateway.auth.Principal;
import com.llm.gateway.cache.CacheService;
import com.llm.gateway.guardrail.GuardrailEngine;
import com.llm.gateway.observability.CostCalculator;
import com.llm.gateway.observability.MetricsRecorder;
import com.llm.gateway.persistence.repository.RequestLogRecord;
import com.llm.gateway.persistence.repository.RequestLogRepository;
import com.llm.gateway.provider.ProviderRegistry;
import com.llm.gateway.ratelimit.QuotaService;
import com.llm.gateway.ratelimit.RateLimiter;
import com.llm.gateway.resilience.ResilientExecutor;
import com.llm.gateway.router.ModelRouter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * 缓存回放中断(客户端断开)的审计:served_model 取缓存响应模型、cache_hit=true、
 * cost=0(回放没有上游调用,旧实现会按估算 token 误计成本且 served_model 为空)。
 */
class GatewayServiceCacheReplayTest {

    @Test
    void replayAbortPersistsCacheHitRecord() throws IOException {
        CacheService cacheService = mock(CacheService.class);
        RequestLogRepository requestLogRepository = mock(RequestLogRepository.class);
        GatewayService service = new GatewayService(
                mock(ApiKeyService.class), mock(RateLimiter.class), mock(QuotaService.class),
                mock(GuardrailEngine.class), cacheService, mock(ModelRouter.class),
                mock(ResilientExecutor.class), mock(ProviderRegistry.class),
                mock(CostCalculator.class), mock(MetricsRecorder.class),
                requestLogRepository, new ObjectMapper());

        ChatCompletionResponse cached = new ChatCompletionResponse(
                "chatcmpl-x", "chat.completion", 1L, "mock-served-model", null, Usage.of(1, 2));
        when(cacheService.lookup(any())).thenReturn(Optional.of(cached));

        // 首帧写出即失败 → SseWriter 把 IOException 转成 ClientDisconnectedException
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(servletResponse.getOutputStream()).thenThrow(new IOException("broken pipe"));

        ChatCompletionRequest request = new ChatCompletionRequest(
                "my-alias", List.of(new ChatMessage("user", "hi")), null, null, null, true, null);
        service.completeStream(request, new Principal("it-tenant", List.of("user"), List.of("*")), servletResponse);

        ArgumentCaptor<RequestLogRecord> captor = ArgumentCaptor.forClass(RequestLogRecord.class);
        verify(requestLogRepository).save(captor.capture());
        RequestLogRecord record = captor.getValue();
        assertThat(record.servedModel()).isEqualTo("mock-served-model");
        assertThat(record.cacheHit()).isTrue();
        assertThat(record.status()).isEqualTo("client_aborted");
        assertThat(record.costUsd()).isZero();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest=GatewayServiceCacheReplayTest
```
Expected: FAIL —— `servedModel` 为 null、`cacheHit` 为 false(走了 persistPartial 通用路径)。

- [ ] **Step 3: GatewayContext 加 servedModel getter**

在 `setServedModel`(56-58 行)之后加:

```java
    public String servedModel() {
        return servedModel;
    }
```

- [ ] **Step 4: GatewayService 三处修改**

(a) `completeStream` 的缓存命中块(约 164-171 行)替换为:

```java
            // 5. 缓存：命中即把完整响应回放成 SSE
            Optional<ChatCompletionResponse> cached = cacheService.lookup(request);
            if (cached.isPresent()) {
                // 回放前先记下模型与命中标记:回放中断时审计不丢 served_model、不误计上游成本
                context.setServedModel(cached.get().model());
                context.markCacheHit();
                replay(writer, cached.get(), request.wantsUsageChunk());
                recordTtft(context, writer);
                finish(context, cached.get(), true);
                return;
            }
```

(b) `ClientDisconnectedException` 分支(约 204-206 行)替换为:

```java
        } catch (ClientDisconnectedException e) {
            log.info("[gateway] 客户端中途断开 reqId={} tenant={}", context.requestId(), context.tenant());
            if (context.cacheHit()) {
                persistCacheReplayAborted(context);
            } else {
                persistPartial(request, context, aggregatorRef.get(), "client_aborted", null);
            }
        }
```

(c) `persistPartial` 首行(约 271 行)替换为:

```java
            String servedModel = aggregator.model() != null ? aggregator.model() : context.servedModel();
```

(d) `persistPartial` 方法之后新增:

```java
    /** 缓存回放中断的落库:没有上游调用,不计成本、不记配额;模型取回放的缓存响应模型。 */
    private void persistCacheReplayAborted(GatewayContext context) {
        try {
            long latencyMs = context.elapsedMillis(System.nanoTime());
            requestLogRepository.save(new RequestLogRecord(
                    context.requestId(), context.tenant(), context.requestedModel(), context.servedModel(),
                    0, 0, 0, 0, 0, 0.0, true, "client_aborted", null, latencyMs));
        } catch (RuntimeException ex) {
            log.warn("写入缓存回放中断审计记录时出错：{}", ex.getMessage());
        }
    }
```

- [ ] **Step 5: 跑测试确认通过 + 流式回归**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test -Dtest='GatewayServiceCacheReplayTest,ChatCompletionStreamIntegrationTest,SseWriterTest,StreamAggregatorTest'
```
Expected: PASS。

- [ ] **Step 6: 后端全量回归**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn -q test
```
Expected: `Tests run: 140+, Failures: 0, Errors: 0`(129 存量 + 本计划新增 ≈15 个用例),BUILD SUCCESS。

---

### Task 10: 前端 —— 会话过期(expiresAt)

**Files:**
- Modify: `llm-gateway-ui/src/auth/session.js`(整文件替换)
- Modify: `llm-gateway-ui/src/router/index.js:60-69`(守卫)
- Modify: `llm-gateway-ui/src/views/Login.vue:22`

- [ ] **Step 1: session.js 整文件替换**

```js
/**
 * 管理端登录态:token、用户名与过期时刻存 localStorage。
 * 过期时刻(epoch 毫秒)来自登录响应 expiresAt;旧会话无该值视为有效,由 401 拦截器兜底。
 */
const TOKEN_KEY = 'gw_admin_token'
const USER_KEY = 'gw_admin_user'
const EXPIRES_KEY = 'gw_admin_expires_at'

export function getToken() { return localStorage.getItem(TOKEN_KEY) || '' }
export function getUsername() { return localStorage.getItem(USER_KEY) || '' }
export function getExpiresAt() {
  const v = localStorage.getItem(EXPIRES_KEY)
  return v ? Number(v) : 0
}

export function setSession(token, username, expiresAt) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username)
  if (expiresAt) localStorage.setItem(EXPIRES_KEY, String(expiresAt))
  else localStorage.removeItem(EXPIRES_KEY)
}

/** 已知过期时刻且已到期(旧会话无 expiresAt 返回 false,交给 401 兜底) */
export function isSessionExpired() {
  const at = getExpiresAt()
  return at > 0 && Date.now() >= at
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(EXPIRES_KEY)
}
```

- [ ] **Step 2: 路由守卫先判过期**

`router/index.js` 顶部 import 改为:

```js
import { getToken, clearSession, isSessionExpired } from '../auth/session'
```

守卫(60-69 行)替换为:

```js
// 全局守卫:已知过期的会话先清理再去登录页(避免发出必 401 的请求);未登录一律去登录页
router.beforeEach((to) => {
  if (getToken() && isSessionExpired()) {
    clearSession()
    if (to.path !== '/login') {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && getToken()) {
    return { path: '/dashboard' }
  }
  return true
})
```

- [ ] **Step 3: Login.vue 传入 expiresAt**

第 22 行:

```js
    setSession(data.token, data.username, data.expiresAt)
```

- [ ] **Step 4: 构建验证**

```bash
cd /c/practice/llm-gateway-ui && npm run build
```
Expected: `vite build` 成功,exit 0。

---

### Task 11: 前端 —— 审计/请求日志时间筛选

**Files:**
- Modify: `llm-gateway-ui/src/views/AuditLogs.vue`
- Modify: `llm-gateway-ui/src/views/Logs.vue`

- [ ] **Step 1: AuditLogs.vue 加时间范围**

script 部分:`query` 声明之后加一行,`load`/`reset` 改为:

```js
const query = reactive({ username: '', action: '', page: 1, size: 20 })
const timeRange = ref([])
```

```js
async function load() {
  loading.value = true
  try {
    const params = { ...query }
    if (timeRange.value && timeRange.value.length === 2) {
      params.from = timeRange.value[0]
      params.to = timeRange.value[1]
    }
    const data = await auditApi.list(params)
    rows.value = data.records || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}
```

```js
function reset() { query.username = ''; query.action = ''; timeRange.value = []; query.page = 1; load() }
```

template 的 toolbar 中,`el-select`(动作)之后、查询按钮之前插入:

```html
        <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
          start-placeholder="开始时间" end-placeholder="结束时间"
          value-format="YYYY-MM-DDTHH:mm:ss" style="width:340px" />
```

- [ ] **Step 2: Logs.vue 加时间范围(同构)**

script:`query` 之后加 `const timeRange = ref([])`;`load` 同样改为拼 `params`(把 `logApi.list({ ...query })` 换成与上面相同的 params 逻辑,调 `logApi.list(params)`);`reset` 改为:

```js
function reset() {
  query.tenant = ''; query.status = ''; query.model = ''; timeRange.value = []; query.page = 1
  load()
}
```

template toolbar 中,模型输入框之后、查询按钮之前插入与 Step 1 相同的 `el-date-picker` 片段。

- [ ] **Step 3: 构建验证**

```bash
cd /c/practice/llm-gateway-ui && npm run build
```
Expected: exit 0。功能表现留冒烟(Task 17)验证。

---

### Task 12: 前端 —— Dashboard 缓存命中列与成本口径标注

**Files:**
- Modify: `llm-gateway-ui/src/views/Dashboard.vue`
- Modify: `llm-gateway-ui/src/router/index.js:15`(dashboard meta.subtitle)

- [ ] **Step 1: Dashboard.vue 三处修改**

(a) 成本卡片标签(cards computed 中):

```js
  { label: '上游总成本 (USD)', value: '$' + totals.value.cost.toFixed(6), icon: Money, tint: '#16a34a' },
```

(b) 页面副标题(page-subtitle div):

```html
        <div class="page-subtitle">按租户聚合的用量与上游成本(缓存命中不计成本;数据源:request_log 表)</div>
```

(c) 表格「请求数」列之后插入缓存命中列,成本列表头改名:

```html
        <el-table-column prop="cacheHits" label="缓存命中" width="110" align="right">
          <template #default="{ row }"><span class="tabular-nums">{{ fmtInt(row.cacheHits) }}</span></template>
        </el-table-column>
```

```html
        <el-table-column label="上游成本 (USD)" width="160" align="right">
```

- [ ] **Step 2: 路由 meta 副标题**

`router/index.js` dashboard 路由 meta:

```js
    meta: { title: '概览', subtitle: '租户用量与上游成本统计', icon: 'DataLine' }
```

- [ ] **Step 3: 构建验证**

```bash
cd /c/practice/llm-gateway-ui && npm run build
```
Expected: exit 0。

---

### Task 13: 后端 Dockerfile + .dockerignore

**Files:**
- Create: `llm-gateway/Dockerfile`
- Create: `llm-gateway/.dockerignore`

- [ ] **Step 1: 新建 Dockerfile**

```dockerfile
# ---- 构建:Maven + JDK 21(依赖层与源码层分开 COPY,提升重建缓存命中)----
# 国内网络受限时:在 RUN mvn 前加一层写入阿里云 mirror 的 settings.xml,例如
#   RUN mkdir -p /root/.m2 && printf '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

# ---- 解层:Spring Boot layered jar 拆成变更频率不同的四层 ----
FROM eclipse-temurin:21-jre AS extract
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- 运行:JRE + 非 root 用户;curl 供 compose healthcheck 使用 ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r gateway && useradd -r -g gateway gateway \
    && mkdir -p /app/logs && chown -R gateway:gateway /app
COPY --from=extract /app/extracted/dependencies/ ./
COPY --from=extract /app/extracted/spring-boot-loader/ ./
COPY --from=extract /app/extracted/snapshot-dependencies/ ./
COPY --from=extract /app/extracted/application/ ./
USER gateway
EXPOSE 8080 9090
# exec 形式:SIGTERM 直达 JVM,graceful shutdown 生效
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

- [ ] **Step 2: 新建 .dockerignore**

```
target/
logs/
.idea/
.claude/
.mvn/
mvnw
mvnw.cmd
Dockerfile
docker-compose.yml
.env
.env.example
```

- [ ] **Step 3: 验证(有 Docker 时)**

```bash
cd /c/practice/llm-gateway && docker build -t llm-gateway:dev .
```
Expected: 构建成功。无 Docker 时跳过(Task 17 降级预案),仅人工复查文件内容。

---

### Task 14: 前端 Dockerfile + nginx.conf + .dockerignore

**Files:**
- Create: `llm-gateway-ui/Dockerfile`
- Create: `llm-gateway-ui/nginx.conf`
- Create: `llm-gateway-ui/.dockerignore`

- [ ] **Step 1: 新建 nginx.conf**

```nginx
# LLM Gateway 管理台:静态托管 + 同源反代(浏览器零跨域)。上游名 gateway 来自 docker-compose 服务名。
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    gzip on;
    gzip_types text/css application/javascript application/json image/svg+xml;

    # 指纹化静态资源长缓存
    location /assets/ {
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    # 管理端 API 反代
    location /admin/ {
        proxy_pass http://gateway:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # OpenAI 兼容 API 反代:SSE 关键配置 —— 不缓冲、长读超时
    location /v1/ {
        proxy_pass http://gateway:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }

    # SPA 路由回退
    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

- [ ] **Step 2: 新建 Dockerfile**

```dockerfile
# ---- 构建:Node 20 + Vite ----
# 国内网络受限时:在 npm ci 前加 RUN npm config set registry https://registry.npmmirror.com
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 同源反代部署:VITE_API_BASE 留空,前端用相对路径经 nginx 反代到后端
RUN npm run build

# ---- 运行:nginx 托管静态文件并反代 /admin、/v1 ----
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 3: 新建 .dockerignore**

```
node_modules/
dist/
.env
.env.*
```

- [ ] **Step 4: 验证(有 Docker 时)**

```bash
cd /c/practice/llm-gateway-ui && docker build -t llm-gateway-ui:dev .
```
Expected: 构建成功。无 Docker 时跳过。

---

### Task 15: docker-compose.yml + .env.example

**Files:**
- Create: `llm-gateway/docker-compose.yml`
- Create: `llm-gateway/.env.example`

- [ ] **Step 1: 新建 docker-compose.yml**

```yaml
# LLM Gateway 单机部署:MySQL + 网关 + 管理台(nginx 同源反代)。
# 使用:cp .env.example .env && 编辑必填项 && docker compose up -d --build
# 前置:前端仓库 llm-gateway-ui 与本仓库同级(build context ../llm-gateway-ui)。
services:
  mysql:
    image: mysql:8
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?请在 .env 设置 MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: llm_gateway
      TZ: Asia/Shanghai
    volumes:
      - mysql-data:/var/lib/mysql
      # 首次启动自动建表/灌种子数据(仅数据卷为空时执行)
      - ./src/main/resources/schema.sql:/docker-entrypoint-initdb.d/1-schema.sql:ro
      - ./src/main/resources/seed.sql:/docker-entrypoint-initdb.d/2-seed.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h127.0.0.1 -uroot -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 5s
      timeout: 3s
      retries: 30
    restart: unless-stopped

  gateway:
    build: .
    depends_on:
      mysql:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: prod
      MYSQL_HOST: mysql
      MYSQL_PORT: "3306"
      MYSQL_DB: llm_gateway
      MYSQL_USER: root
      MYSQL_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      GATEWAY_JWT_SECRET: ${GATEWAY_JWT_SECRET:?请在 .env 设置 GATEWAY_JWT_SECRET(至少 32 字符)}
      ADMIN_USERNAME: ${ADMIN_USERNAME:-}
      ADMIN_PASSWORD: ${ADMIN_PASSWORD:-}
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY:-}
      OPENAI_API_KEY: ${OPENAI_API_KEY:-}
      ANTHROPIC_API_KEY: ${ANTHROPIC_API_KEY:-}
      GATEWAY_ADMIN_ALLOWED_ORIGINS: ${GATEWAY_ADMIN_ALLOWED_ORIGINS:-}
      TZ: Asia/Shanghai
    # 刻意不映射 8080/9090 到宿主机:业务流量经 ui 反代,Actuator 仅容器网络内可达
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:9090/actuator/health"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 30s
    restart: unless-stopped

  ui:
    build:
      context: ../llm-gateway-ui
    depends_on:
      gateway:
        condition: service_healthy
    ports:
      - "${UI_PORT:-8081}:80"
    restart: unless-stopped

volumes:
  mysql-data:
```

- [ ] **Step 2: 新建 .env.example**

```bash
# ===== 必填 =====
# MySQL root 密码(容器内数据库;网关用它连库)
MYSQL_ROOT_PASSWORD=change-me
# JWT 签名密钥,至少 32 字符,缺失或过短网关拒绝启动
GATEWAY_JWT_SECRET=change-me-to-a-random-string-of-32-chars-min

# ===== 首启引导管理员(仅 admin_user 表为空时创建)=====
ADMIN_USERNAME=admin
ADMIN_PASSWORD=change-me

# ===== 供应商密钥(留空则该供应商不可用,会自动 Fallback)=====
DEEPSEEK_API_KEY=
OPENAI_API_KEY=
ANTHROPIC_API_KEY=

# ===== 可选 =====
# 管理端跨域白名单(逗号分隔)。同源反代部署留空;分域部署填前端来源,如 https://admin.example.com
GATEWAY_ADMIN_ALLOWED_ORIGINS=
# 管理台对外端口
UI_PORT=8081
```

- [ ] **Step 3: 验证配置语法(有 Docker 时)**

```bash
cd /c/practice/llm-gateway && cp .env.example .env.compose-check && docker compose --env-file .env.compose-check config >/dev/null && rm .env.compose-check
```
Expected: exit 0(compose 文件与变量插值合法)。无 Docker 时人工复查缩进与服务名(nginx.conf 的上游名 `gateway` 必须与服务名一致)。

---

### Task 16: README 部署章节(两仓库)

**Files:**
- Modify: `llm-gateway/README.md`(运行方式之后加部署章节;可观测端点描述更新)
- Modify: `llm-gateway-ui/README.md`(加生产部署说明)

- [ ] **Step 1: llm-gateway/README.md 加「Docker Compose 部署」章节**

在「运行方式」章节之后插入:

````markdown
## Docker Compose 部署(单机生产)

前置:Docker 20+ 与 Docker Compose v2;前端仓库 `llm-gateway-ui` 与本仓库同级目录。

```bash
cp .env.example .env   # 编辑必填项:MYSQL_ROOT_PASSWORD、GATEWAY_JWT_SECRET(≥32 字符)、ADMIN_USERNAME/PASSWORD
docker compose up -d --build
```

拓扑与端口:

| 服务 | 端口 | 说明 |
|---|---|---|
| ui(nginx) | 宿主机 `${UI_PORT:-8081}` | 托管管理台,`/admin`、`/v1` 同源反代到网关(浏览器零跨域,SSE 不缓冲) |
| gateway | 8080(仅容器网络) | 业务端口,不映射宿主机 |
| gateway | 9090(仅容器网络) | Actuator 管理端口:`/actuator/health|metrics|prometheus`,供 healthcheck 与 Prometheus 抓取 |
| mysql | 3306(仅容器网络) | 数据持久化在 named volume `mysql-data`,首启自动执行 schema.sql/seed.sql |

- API 调用:`http://<host>:${UI_PORT}/v1/chat/completions`(Bearer sk-gw- 开头的 API Key)。
- 日志:`docker compose logs -f gateway`(控制台),容器内 `/app/logs/gateway.log`(按天+100MB 滚动,保留 14 天);每行含 traceId,与响应头 `X-Request-Id`、`request_log.request_id` 同 ID。
- 优雅停机:`docker compose stop`(SIGTERM)后不再接新请求,进行中的请求(含 SSE 流)最多 30s 收尾。
````

- [ ] **Step 2: llm-gateway/README.md 更新可观测端点描述**

找到原「可观测端点」描述(约 159 行,写着 `/actuator/health`、`/actuator/metrics`、`/actuator/prometheus` 公开),改为:

```markdown
- 可观测:Actuator 在独立管理端口 9090(`GATEWAY_MANAGEMENT_PORT` 可覆盖),主端口 8080 不暴露 `/actuator/**`;生产部署时 9090 不映射宿主机,仅容器网络内供 healthcheck 与 Prometheus 抓取。
```

- [ ] **Step 3: llm-gateway-ui/README.md 加生产部署说明**

在构建说明(`npm run build`)之后加:

````markdown
## 生产部署

本仓库自带 `Dockerfile` + `nginx.conf`:nginx 托管 `dist/` 并把 `/admin`、`/v1` 同源反代到后端容器(SPA 路由回退、SSE 不缓冲)。**由后端仓库 `llm-gateway/docker-compose.yml` 统一编排**,无需单独部署:

```bash
cd ../llm-gateway && docker compose up -d --build
```

同源反代下 `VITE_API_BASE` 保持留空(相对路径);仅分域部署时才需要设置它并在后端配 `GATEWAY_ADMIN_ALLOWED_ORIGINS`。
````

- [ ] **Step 4: 验证**

通读两处 README 改动,确认端口、变量名与 compose/Dockerfile 一致(`UI_PORT`、9090、`GATEWAY_ADMIN_ALLOWED_ORIGINS`)。

---

### Task 17: 全量回归 + 冒烟(含降级预案)

**Files:** 无代码改动(验证任务)

- [ ] **Step 1: 后端全量测试**

```bash
cd /c/practice/llm-gateway && JAVA_HOME=$HOME/.jdks/ms-21.0.10 mvn test
```
Expected: `Tests run: 140+, Failures: 0, Errors: 0`,BUILD SUCCESS。

- [ ] **Step 2: 前端构建**

```bash
cd /c/practice/llm-gateway-ui && npm run build
```
Expected: exit 0。

- [ ] **Step 3: 检查 Docker 可用性(决定冒烟路径)**

```bash
docker --version && docker compose version
```
可用 → 走 Step 4A(compose 全链路);不可用 → 走 Step 4B(本地降级)。

- [ ] **Step 4A: Compose 全链路冒烟(Docker 可用)**

```bash
cd /c/practice/llm-gateway
cp .env.example .env    # 手动编辑:MYSQL_ROOT_PASSWORD/GATEWAY_JWT_SECRET/ADMIN_USERNAME/ADMIN_PASSWORD 填真实值
docker compose up -d --build
docker compose ps       # 三个服务 healthy/running
```

逐项验证(全部 curl 加 `--noproxy '*'`):

1. 管理台可达:`curl --noproxy '*' -s -o /dev/null -w '%{http_code}' http://localhost:8081/` → `200`
2. 登录含 expiresAt:`curl --noproxy '*' -s -X POST http://localhost:8081/admin/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"<你的密码>"}'` → JSON 含 `"token"`、`"expiresAt"`
3. 401 带 CORS 头(模拟分域;同源下该头由白名单控制,默认 prod 为空则无头——改用验证 401 结构):`curl --noproxy '*' -i http://localhost:8081/admin/logs` → `401` + `{"code":401,...}` + 响应头含 `X-Request-Id`
4. 流式(需已配任一供应商 key 或用 seed 的 mock 路由):`curl --noproxy '*' -N http://localhost:8081/v1/chat/completions -H 'Authorization: Bearer <sk-gw-key>' -H 'Content-Type: application/json' -d '{"model":"default","messages":[{"role":"user","content":"hi"}],"stream":true}'` → 逐帧 `data: ...`,以 `data: [DONE]` 结束
5. Actuator 仅内网:`docker compose exec gateway curl -fsS http://localhost:9090/actuator/health` → `{"status":"UP"...}`;`docker compose exec gateway curl -fsS http://localhost:9090/actuator/prometheus | head -5` → Prometheus 文本;宿主机 `curl --noproxy '*' http://localhost:9090/actuator/health` → 连接拒绝(端口未映射)
6. 主端口无 actuator:`docker compose exec gateway curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health` → `404`
7. 文件日志与 traceId:`docker compose exec gateway sh -c 'head -3 logs/gateway.log'` → 新 pattern 且方括号内有 16 位 traceId
8. UI 手动:浏览器开 `http://localhost:8081` → 登录 → Logs/操作审计页用时间范围筛选查询 → Dashboard 看到「缓存命中」列 → Playground 流式正常
9. 优雅停机:`docker compose stop gateway` → `docker compose logs gateway | tail -20` 出现 graceful shutdown 相关日志(如 `Commencing graceful shutdown`),容器在 30s 内退出
10. 收尾:`docker compose down`(保留 volume)

- [ ] **Step 4B: 本地降级冒烟(无 Docker)**

```bash
# 先确认端口无残留
netstat -ano | grep -E ':(8080|9090)' || echo clean
cd /c/practice/llm-gateway
JAVA_HOME=$HOME/.jdks/ms-21.0.10 GATEWAY_JWT_SECRET=local-smoke-secret-0123456789abcdef mvn spring-boot:run
```

另开终端验证:

1. `curl --noproxy '*' http://localhost:9090/actuator/health` → `{"status":"UP"...}`(端口分离生效)
2. `curl --noproxy '*' -s http://localhost:9090/actuator/prometheus | head -5` → Prometheus 文本(registry 依赖生效)
3. `curl --noproxy '*' -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health` → `404`
4. `curl --noproxy '*' -i -H 'Origin: http://localhost:5173' http://localhost:8080/admin/logs` → `401` 且响应头含 `Access-Control-Allow-Origin: http://localhost:5173` 与 `X-Request-Id`
5. 登录响应含 `expiresAt`(同 4A-2,端口换 8080)
6. 控制台日志为新 pattern 且带 traceId
7. 前端:`cd /c/practice/llm-gateway-ui && npm run dev`,浏览器验证登录、时间筛选、Dashboard 缓存命中列、Playground 流式
8. Ctrl+C 停网关,观察 graceful shutdown 日志
9. 记录:compose 链路(Dockerfile/nginx/compose)未实机验证,留待有 Docker 环境执行 Step 4A

- [ ] **Step 5: 汇总报告**

向用户报告:测试数(全绿)、冒烟路径(4A 或 4B)与各项结果、遗留事项(如走 4B 则 compose 未实机验证)。

---

## Self-Review 记录(计划完成后自检)

1. **Spec 覆盖:** §2.1 管理端口/依赖→Task 1;§2.2 CORS+401→Task 2;§2.3 顺序→Task 2/3;§3.1 TraceId→Task 3;§3.2 logback→Task 4;§3.3 优雅停机→Task 1(配置)+Task 17(验证);§4.1→Task 13;§4.2→Task 14;§4.3→Task 15;§4.4→Task 4/16;§4.5→Task 17;§5.1→Task 6/11;§5.2→Task 5/10;§5.3→Task 7;§5.4→Task 9;§5.5→Task 8/12;§7 测试→各任务+Task 17。无遗漏。
2. **占位符:** 无 TBD/TODO;所有代码步骤给出完整代码;冒烟里的 `<你的密码>`/`<sk-gw-key>` 是运行期用户输入,非计划占位。
3. **类型一致:** `LoginResult(token, expiresAtMillis)` 在 Task 5 定义、Task 5 controller 使用;`StatRow` 五参在 Task 8 定义并与测试 jsonPath 字段(`cacheHits`)一致;`TraceIdFilter.MDC_KEY/HEADER/newTraceId()` 在 Task 3 定义、Task 3(GatewayService)与 Task 4(logback pattern 注释)引用一致;`AdminTestTokens.TEST_SECRET/issue()` 在 Task 6 定义、Task 6/7/8 使用;前端 `setSession(token, username, expiresAt)` 三参在 Task 10 内一致。
