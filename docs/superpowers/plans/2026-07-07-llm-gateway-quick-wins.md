# LLM Gateway 速赢优化包 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 llm-gateway 前后端审查发现的高优先级性能/健壮性问题(超时、退避、配额缓存、N+1、前端校验卡死、CRUD 重复等)。

**Architecture:** 后端在现有 Spring Boot 组件内做最小侵入修补(不引入新依赖——限流/熔断未来会迁 Spring Cloud Alibaba/Sentinel);前端抽取 `useCrudDialog` composable 统一三个 CRUD 页面并修复错误处理。

**Tech Stack:** Spring Boot 3 + MyBatis-Plus + MySQL(后端);Vue 3 + Element Plus + Vite(前端)。

**注意:两个项目都不是 git 仓库,所有"Commit"步骤替换为编译/测试验证。**

---

## Task 1: 后端 — HTTP 超时配置(GatewayProperties + Provider)

**Files:**
- Modify: `llm-gateway/src/main/java/com/llm/gateway/config/GatewayProperties.java`
- Modify: `llm-gateway/src/main/resources/application.yaml`
- Modify: `llm-gateway/src/test/java/com/llm/gateway/Fixtures.java`
- Create: `llm-gateway/src/main/java/com/llm/gateway/provider/RestClients.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/provider/OpenAiCompatibleProvider.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/provider/ProvidersConfig.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/provider/AnthropicProvider.java`

- [ ] **Step 1: GatewayProperties 增加 Http 配置节**

在 `GatewayProperties` record 的组件列表末尾(`Resilience resilience` 之后)加 `Http http`,并在类体内加嵌套 record:

```java
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Routing routing,
        Llm llm,
        Map<String, ProviderConfig> providers,
        RateLimit rateLimit,
        Quota quota,
        Cache cache,
        Guardrail guardrail,
        Resilience resilience,
        Http http) {
```

```java
    /**
     * 出站 HTTP 客户端配置(调用各 LLM 供应商)。
     *
     * @param connectTimeoutMs 连接超时毫秒
     * @param readTimeoutMs    读超时毫秒
     */
    public record Http(int connectTimeoutMs, int readTimeoutMs) {
    }
```

- [ ] **Step 2: application.yaml 增加 gateway.http**

在 `resilience:` 段之后追加:

```yaml
  # ---- 出站 HTTP(调用供应商)超时 ----
  http:
    connect-timeout-ms: 5000
    read-timeout-ms: 30000
```

- [ ] **Step 3: 更新 Fixtures**

`Fixtures.properties(...)` 的 `new GatewayProperties(...)` 最后一个参数后追加:

```java
                new Resilience(maxRetries, new CircuitBreakerConfig(cbThreshold, cbOpenSeconds)),
                new GatewayProperties.Http(5000, 30000));
```

- [ ] **Step 4: 新建 RestClients 工厂**

Create `llm-gateway/src/main/java/com/llm/gateway/provider/RestClients.java`:

```java
package com.llm.gateway.provider;

import java.time.Duration;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.llm.gateway.config.GatewayProperties;

/**
 * 供应商 RestClient 工厂:统一配置连接/读超时,避免供应商挂起时网关线程被永久占用。
 */
public final class RestClients {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30_000;

    private RestClients() {
    }

    /**
     * 构造带超时的 RestClient。
     *
     * @param baseUrl API 基地址
     * @param http    超时配置(可为 null,用默认值)
     * @return 配置好的 RestClient
     */
    public static RestClient create(String baseUrl, GatewayProperties.Http http) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(http == null ? DEFAULT_CONNECT_TIMEOUT_MS : http.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(http == null ? DEFAULT_READ_TIMEOUT_MS : http.readTimeoutMs()));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
```

- [ ] **Step 5: OpenAiCompatibleProvider 使用工厂**

构造函数追加 `GatewayProperties.Http http` 参数,替换 RestClient 构造:

```java
    public OpenAiCompatibleProvider(String name, String baseUrl, String apiKey, ObjectMapper objectMapper,
                                    GatewayProperties.Http http) {
        this.name = name;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = RestClients.create(baseUrl, http);
    }
```

并加 import `com.llm.gateway.config.GatewayProperties`。

- [ ] **Step 6: ProvidersConfig 传入 http 配置**

`build(...)` 最后一行改为:

```java
        return new OpenAiCompatibleProvider(name, baseUrl, apiKey, objectMapper, properties.http());
```

- [ ] **Step 7: AnthropicProvider 使用工厂**

构造函数中 `this.restClient = RestClient.builder().baseUrl(baseUrl).build();` 改为:

```java
        this.restClient = RestClients.create(baseUrl, properties.http());
```

(可删除不再使用的 `import org.springframework.web.client.RestClient;`)

- [ ] **Step 8: 编译验证**

Run: `cd C:/practice/llm-gateway && ./mvnw -q compile`
Expected: BUILD SUCCESS(无输出即成功)。若有其它调用 `new OpenAiCompatibleProvider(` 或 `Fixtures.properties` 的编译错误,按同样方式补参数。

---

## Task 2: 后端 — HikariCP 连接池 + MyBatis 语句超时

**Files:**
- Modify: `llm-gateway/src/main/resources/application.yaml`

- [ ] **Step 1: datasource 下加 hikari 配置**

```yaml
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/${MYSQL_DB:llm_gateway}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASSWORD:123456}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      idle-timeout: 600000
      max-lifetime: 1800000
```

- [ ] **Step 2: MyBatis-Plus 全局语句超时(5 秒)**

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    default-statement-timeout: 5
```

- [ ] **Step 3: 编译验证**

Run: `cd C:/practice/llm-gateway && ./mvnw -q compile`
Expected: BUILD SUCCESS。

---

## Task 3: 后端 — 重试指数退避(TDD)

**Files:**
- Modify: `llm-gateway/src/main/java/com/llm/gateway/resilience/ResilientExecutor.java`
- Test: `llm-gateway/src/test/java/com/llm/gateway/resilience/ResilientExecutorTest.java`

- [ ] **Step 1: 先读现有测试**

Read `ResilientExecutorTest.java`,沿用其构造 executor 与 fake invoker 的方式写新测试。

- [ ] **Step 2: 写失败测试(重试之间有退避延迟)**

在 `ResilientExecutorTest` 中追加(按现有测试的夹具风格调整构造代码):

```java
    @Test
    void retriesBackOffBetweenAttempts() {
        // maxRetries=2 → 同一目标共 3 次尝试,之间应有 ≥ 100+200=300ms 退避
        ResilientExecutor executor = new ResilientExecutor(
                new CircuitBreakerRegistry(Fixtures.properties()), Fixtures.properties(60, 300, 1_000_000L, 5, 30, 2));
        long start = System.nanoTime();
        assertThatThrownBy(() -> executor.execute(
                new RouteDecision(List.of(new ProviderTarget("mock", "m1"))),
                target -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(NoProviderAvailableException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
    }
```

(若 `RouteDecision`/`CircuitBreakerRegistry` 构造签名不同,以现有测试中的用法为准。)

- [ ] **Step 3: 运行测试确认失败**

Run: `cd C:/practice/llm-gateway && ./mvnw -q test -Dtest=ResilientExecutorTest`
Expected: FAIL — elapsedMs < 300(当前无退避,几乎瞬间完成)。

- [ ] **Step 4: 实现退避**

`ResilientExecutor` 中加常量与方法,并在 catch 块末尾调用:

```java
    private static final long BACKOFF_BASE_MS = 100;
    private static final long BACKOFF_MAX_MS = 1_000;
```

catch 块改为:

```java
                } catch (Exception e) {
                    lastError = e;
                    breaker.onFailure();
                    log.warn("目标 {} 调用失败（第 {} 次尝试）：{}", target, attempt + 1, e.getMessage());
                    if (attempt < maxRetries && !backoff(attempt)) {
                        throw new NoProviderAvailableException("重试等待被中断", e);
                    }
                }
```

新增私有方法:

```java
    /**
     * 指数退避:第 n 次重试前等待 min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2^n) 毫秒,
     * 避免紧循环重试压垮已故障的供应商。
     *
     * @param attempt 刚失败的尝试序号(从 0 开始)
     * @return true 表示等待完成;false 表示线程被中断(已恢复中断标志)
     */
    private boolean backoff(int attempt) {
        long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * (1L << attempt));
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd C:/practice/llm-gateway && ./mvnw -q test -Dtest=ResilientExecutorTest`
Expected: PASS(全部用例,含原有用例)。

---

## Task 4: 后端 — 配额检查内存缓存(TDD)

**Files:**
- Modify: `llm-gateway/src/main/java/com/llm/gateway/ratelimit/QuotaService.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/core/GatewayService.java:150-153`
- Create test: `llm-gateway/src/test/java/com/llm/gateway/ratelimit/QuotaServiceTest.java`

- [ ] **Step 1: 写失败测试**

Create `QuotaServiceTest.java`(spring-boot-starter-test 自带 Mockito):

```java
package com.llm.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.llm.gateway.Fixtures;
import com.llm.gateway.exception.QuotaExceededException;
import com.llm.gateway.persistence.repository.RequestLogRepository;

class QuotaServiceTest {

    @Test
    void cachesDbAggregateWithinTtl() {
        RequestLogRepository repo = mock(RequestLogRepository.class);
        when(repo.sumTokensByTenant(anyString())).thenReturn(100L);
        QuotaService service = new QuotaService(repo, Fixtures.properties());

        service.checkQuota("t1");
        service.checkQuota("t1");
        service.checkQuota("t1");

        // TTL 内只应查一次库
        verify(repo, times(1)).sumTokensByTenant("t1");
    }

    @Test
    void recordUsageAccumulatesLocallyAndTripsQuota() {
        RequestLogRepository repo = mock(RequestLogRepository.class);
        when(repo.sumTokensByTenant(anyString())).thenReturn(0L);
        // 配额上限 1000
        QuotaService service = new QuotaService(repo, Fixtures.properties(60, 300, 1000L, 5, 30, 2));

        assertThatCode(() -> service.checkQuota("t1")).doesNotThrowAnyException();
        service.recordUsage("t1", 1000);
        assertThatThrownBy(() -> service.checkQuota("t1"))
                .isInstanceOf(QuotaExceededException.class);
        // 本地累加生效,无需再查库
        verify(repo, times(1)).sumTokensByTenant("t1");
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd C:/practice/llm-gateway && ./mvnw -q test -Dtest=QuotaServiceTest`
Expected: 编译失败 — `recordUsage` 不存在;且第一个用例会 verify 失败(当前每次都查库)。

- [ ] **Step 3: 实现 QuotaService 缓存**

替换 `QuotaService.java` 主体:

```java
package com.llm.gateway.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.llm.gateway.config.GatewayProperties;
import com.llm.gateway.exception.QuotaExceededException;
import com.llm.gateway.persistence.repository.RequestLogRepository;

/**
 * 租户级 Token 配额服务:以 {@code request_log} 表为真值源,内存缓存 60s 内的用量,
 * 请求成功后本地累加——避免每个请求都对日志表做全表聚合。
 */
@Service
public class QuotaService {

    private static final long CACHE_TTL_MS = 60_000;

    private final RequestLogRepository requestLogRepository;
    private final long limitPerTenant;
    private final ConcurrentHashMap<String, CachedUsage> usageCache = new ConcurrentHashMap<>();

    /**
     * 单租户用量缓存:DB 快照 + 快照后的本地增量。
     */
    private record CachedUsage(long loadedAtMs, AtomicLong tokens) {
    }

    public QuotaService(RequestLogRepository requestLogRepository, GatewayProperties properties) {
        this.requestLogRepository = requestLogRepository;
        this.limitPerTenant = properties.quota().tokensPerTenant();
    }

    /**
     * 调用前预检查:若该租户已消耗的 Token 达到上限则抛出 {@link QuotaExceededException}。
     *
     * @param tenant 租户标识
     */
    public void checkQuota(String tenant) {
        if (consumedTokens(tenant) >= limitPerTenant) {
            throw new QuotaExceededException(
                    "租户 [" + tenant + "] 的 Token 配额已用尽（上限 " + limitPerTenant + "）");
        }
    }

    /**
     * 请求成功后本地累加用量,保证 TTL 窗口内配额判断仍然准确。
     *
     * @param tenant 租户标识
     * @param tokens 本次消耗的 Token 数
     */
    public void recordUsage(String tenant, long tokens) {
        CachedUsage cached = usageCache.get(tenant);
        if (cached != null) {
            cached.tokens().addAndGet(tokens);
        }
    }

    /**
     * 查询某租户已消耗的 Token 数(TTL 内走缓存)。
     *
     * @param tenant 租户标识
     * @return 已消耗 Token 数
     */
    public long consumedTokens(String tenant) {
        long now = System.currentTimeMillis();
        CachedUsage cached = usageCache.compute(tenant, (t, old) -> {
            if (old != null && now - old.loadedAtMs() < CACHE_TTL_MS) {
                return old;
            }
            return new CachedUsage(now, new AtomicLong(requestLogRepository.sumTokensByTenant(t)));
        });
        return cached.tokens().get();
    }
}
```

- [ ] **Step 4: GatewayService 成功收尾时记账**

`GatewayService.finish(...)` 中,`requestLogRepository.save(...)` 之后、`log.info` 之前加:

```java
        quotaService.recordUsage(context.tenant(), totalTokens);
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd C:/practice/llm-gateway && ./mvnw -q test -Dtest=QuotaServiceTest`
Expected: PASS(2 个用例)。

---

## Task 5: 后端 — 路由规则 N+1 修复

**Files:**
- Modify: `llm-gateway/src/main/java/com/llm/gateway/persistence/repository/impl/RoutingRuleRepositoryImpl.java`
- Modify: `llm-gateway/src/main/java/com/llm/gateway/admin/RoutingRuleAdminService.java:30-33, 96-116`

- [ ] **Step 1: RoutingRuleRepositoryImpl 批量加载**

替换 `findAll()` 与 `toRecord(...)`:

```java
    @Override
    public List<RoutingRuleRecord> findAll() {
        // 一次查全所有降级链,内存按别名分组,避免每条规则一次查询(N+1)
        Map<String, List<ProviderTarget>> fallbacksByAlias = fallbackMapper.selectList(
                        Wrappers.<RoutingFallbackEntity>lambdaQuery().orderByAsc(RoutingFallbackEntity::getSeq))
                .stream()
                .collect(Collectors.groupingBy(RoutingFallbackEntity::getRuleAlias,
                        Collectors.mapping(f -> new ProviderTarget(f.getProvider(), f.getModel()),
                                Collectors.toList())));
        return ruleMapper.selectList(null).stream()
                .map(rule -> toRecord(rule, fallbacksByAlias.getOrDefault(rule.getAlias(), List.of())))
                .toList();
    }

    /**
     * 把规则实体(连同其降级链)转换成领域记录。
     *
     * @param rule      规则实体
     * @param fallbacks 该规则的降级链
     * @return 路由规则记录
     */
    private RoutingRuleRecord toRecord(RoutingRuleEntity rule, List<ProviderTarget> fallbacks) {
        ProviderTarget primary = new ProviderTarget(rule.getPrimaryProvider(), rule.getPrimaryModel());
        ProviderTarget escalateTo = rule.getEscalateProvider() == null ? null
                : new ProviderTarget(rule.getEscalateProvider(), rule.getEscalateModel());
        return new RoutingRuleRecord(rule.getAlias(), primary, fallbacks, rule.getMaxPromptTokens(), escalateTo);
    }
```

顶部补 import:

```java
import java.util.Map;
import java.util.stream.Collectors;
```

- [ ] **Step 2: RoutingRuleAdminService.list() 批量加载**

替换 `list()` 与 `toView(...)`:

```java
    /** @return 所有规则视图（含降级链） */
    public List<RoutingRuleView> list() {
        Map<String, List<RoutingFallbackEntity>> fallbacksByAlias = fallbackMapper.selectList(
                        Wrappers.<RoutingFallbackEntity>lambdaQuery().orderByAsc(RoutingFallbackEntity::getSeq))
                .stream()
                .collect(Collectors.groupingBy(RoutingFallbackEntity::getRuleAlias));
        return ruleMapper.selectList(Wrappers.<RoutingRuleEntity>lambdaQuery().orderByAsc(RoutingRuleEntity::getId))
                .stream()
                .map(rule -> toView(rule, fallbacksByAlias.getOrDefault(rule.getAlias(), List.of())))
                .toList();
    }
```

```java
    /**
     * 实体（含降级链）转视图。
     *
     * @param rule      规则实体
     * @param fallbacks 该规则的降级链实体
     * @return 视图
     */
    private RoutingRuleView toView(RoutingRuleEntity rule, List<RoutingFallbackEntity> fallbacks) {
        RoutingRuleView view = new RoutingRuleView();
        view.setId(rule.getId());
        view.setAlias(rule.getAlias());
        view.setPrimaryProvider(rule.getPrimaryProvider());
        view.setPrimaryModel(rule.getPrimaryModel());
        view.setMaxPromptTokens(rule.getMaxPromptTokens());
        view.setEscalateProvider(rule.getEscalateProvider());
        view.setEscalateModel(rule.getEscalateModel());
        view.setFallbacks(fallbacks.stream().map(f -> {
            RoutingRuleView.Fallback fb = new RoutingRuleView.Fallback();
            fb.setSeq(f.getSeq());
            fb.setProvider(f.getProvider());
            fb.setModel(f.getModel());
            return fb;
        }).toList());
        return view;
    }
```

补 import:`java.util.Map`、`java.util.stream.Collectors`。

- [ ] **Step 3: 全量测试验证**

Run: `cd C:/practice/llm-gateway && ./mvnw -q test`
Expected: BUILD SUCCESS,所有测试通过。

---

## Task 6: 前端 — useCrudDialog composable(修校验卡死 + 删除错误处理 + 删除 loading)

**Files:**
- Create: `llm-gateway-ui/src/composables/useCrudDialog.js`
- Modify: `llm-gateway-ui/src/views/ApiKeys.vue`
- Modify: `llm-gateway-ui/src/views/Pricing.vue`
- Modify: `llm-gateway-ui/src/views/RoutingRules.vue`

- [ ] **Step 1: 新建 composable**

Create `llm-gateway-ui/src/composables/useCrudDialog.js`:

```javascript
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 通用 CRUD 弹窗逻辑:列表加载、新增/编辑弹窗、提交(校验失败不卡 saving)、删除(带确认与按行 loading)。
 *
 * @param {Object} options
 * @param {Object} options.api        含 list/create/update/remove 的 API 对象
 * @param {Function} options.blankForm 返回一份空白表单对象的工厂
 * @param {Function} [options.confirmText] (row) => 删除确认文案
 * @param {Function} [options.buildPayload] (form) => 提交前对表单做清洗,默认浅拷贝
 */
export function useCrudDialog({ api, blankForm, confirmText, buildPayload }) {
  const loading = ref(false)
  const rows = ref([])
  const dialog = reactive({ visible: false, mode: 'create', saving: false })
  const formRef = ref()
  const form = reactive(blankForm())
  const deleting = reactive({})

  async function load() {
    loading.value = true
    try { rows.value = await api.list() } finally { loading.value = false }
  }

  function openCreate() {
    Object.assign(form, blankForm())
    dialog.mode = 'create'
    dialog.visible = true
  }

  function openEdit(row) {
    Object.assign(form, blankForm(), JSON.parse(JSON.stringify(row)))
    dialog.mode = 'edit'
    dialog.visible = true
  }

  async function submit() {
    try {
      await formRef.value.validate()
    } catch {
      return // 校验失败:让用户改完再试,不进入 saving 状态
    }
    dialog.saving = true
    try {
      const payload = buildPayload ? buildPayload(form) : { ...form }
      if (dialog.mode === 'create') await api.create(payload)
      else await api.update(form.id, payload)
      ElMessage.success('保存成功')
      dialog.visible = false
      load()
    } catch (e) {
      /* 错误已由拦截器提示,保留弹窗让用户重试 */
    } finally {
      dialog.saving = false
    }
  }

  async function remove(row) {
    await ElMessageBox.confirm(confirmText ? confirmText(row) : '确认删除该记录?', '删除确认', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger'
    })
    deleting[row.id] = true
    try {
      await api.remove(row.id)
      ElMessage.success('已删除')
      load()
    } catch (e) {
      /* 错误已由拦截器提示 */
    } finally {
      deleting[row.id] = false
    }
  }

  return { loading, rows, dialog, formRef, form, deleting, load, openCreate, openEdit, submit, remove }
}
```

- [ ] **Step 2: 改造 ApiKeys.vue 的 script**

`<script setup>` 整体替换为:

```javascript
import { onMounted } from 'vue'
import { Plus, Refresh, Edit, Delete } from '@element-plus/icons-vue'
import { apiKeyApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

const { loading, rows, dialog, formRef, form, deleting, load, openCreate, openEdit, submit, remove } =
  useCrudDialog({
    api: apiKeyApi,
    blankForm: () => ({ id: null, apiKey: '', tenant: '', roles: 'user', allowedModels: '*', enabled: true }),
    confirmText: (row) => `确认删除 API Key「${row.apiKey}」?`,
    buildPayload: (f) => ({ ...f, enabled: f.enabled !== false })
  })

const rules = {
  apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  tenant: [{ required: true, message: '请输入租户', trigger: 'blur' }],
  allowedModels: [{ required: true, message: '请输入可用模型(逗号分隔,* 表示全部)', trigger: 'blur' }]
}

onMounted(load)
```

模板中删除按钮加 loading(`ApiKeys.vue:96`):

```html
<el-button link type="danger" :loading="deleting[row.id]" @click="remove(row)"><el-icon><Delete /></el-icon>删除</el-button>
```

注意:原 `openEdit` 有 `enabled: row.enabled !== false` 归一化,现移到 `buildPayload`;编辑弹窗打开时开关按原始值显示,`blankForm()` 先展开保证字段齐全。

- [ ] **Step 3: 改造 Pricing.vue 的 script**

`<script setup>` 整体替换为:

```javascript
import { onMounted } from 'vue'
import { Plus, Refresh, Edit, Delete } from '@element-plus/icons-vue'
import { pricingApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

const { loading, rows, dialog, formRef, form, deleting, load, openCreate, openEdit, submit, remove } =
  useCrudDialog({
    api: pricingApi,
    blankForm: () => ({ id: null, model: '', inputPer1k: 0, outputPer1k: 0 }),
    confirmText: (row) => `确认删除模型「${row.model}」的计费?`
  })

const rules = {
  model: [{ required: true, message: '请输入模型名', trigger: 'blur' }]
}

onMounted(load)
```

模板删除按钮同样加 `:loading="deleting[row.id]"`。

- [ ] **Step 4: 改造 RoutingRules.vue 的 script**

`<script setup>` 整体替换为(保留 providers 加载与 fallback 编辑函数):

```javascript
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, Edit, Delete, Right } from '@element-plus/icons-vue'
import { routingApi, metaApi } from '../api'
import { useCrudDialog } from '../composables/useCrudDialog'

const providers = ref([])

const blankForm = () => ({
  id: null, alias: '', primaryProvider: '', primaryModel: '',
  maxPromptTokens: null, escalateProvider: '', escalateModel: '', fallbacks: []
})

const { loading, rows, dialog, formRef, form, deleting, load, openCreate, openEdit: baseOpenEdit, submit, remove } =
  useCrudDialog({
    api: routingApi,
    blankForm,
    confirmText: (row) => `确认删除路由别名「${row.alias}」?`,
    buildPayload: (f) => ({ ...f, fallbacks: f.fallbacks.filter((x) => x.provider && x.model) })
  })

function openEdit(row) {
  baseOpenEdit(row)
  if (!Array.isArray(form.fallbacks)) form.fallbacks = []
}

function addFallback() { form.fallbacks.push({ provider: '', model: '' }) }
function removeFallback(i) { form.fallbacks.splice(i, 1) }

const rules = {
  alias: [{ required: true, message: '请输入别名', trigger: 'blur' }],
  primaryProvider: [{ required: true, message: '请选择首选供应商', trigger: 'change' }],
  primaryModel: [{ required: true, message: '请输入首选模型', trigger: 'blur' }]
}

onMounted(async () => {
  load()
  try {
    providers.value = (await metaApi.get()).providers || []
  } catch (e) {
    ElMessage.warning('供应商列表加载失败,可手动输入供应商名')
  }
})
```

模板删除按钮加 `:loading="deleting[row.id]"`。

- [ ] **Step 5: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功,无报错。

---

## Task 7: 前端 — API 地址环境变量

**Files:**
- Modify: `llm-gateway-ui/src/api/http.js:9-12`
- Create: `llm-gateway-ui/.env.example`

- [ ] **Step 1: http.js 使用环境变量**

```javascript
const http = axios.create({
  // 生产部署时通过 VITE_API_BASE 指定后端地址;开发期留空走 Vite proxy
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 30000
})
```

- [ ] **Step 2: 新建 .env.example**

```
# 后端 API 地址(留空 = 同源/开发期 Vite proxy)
VITE_API_BASE=
```

- [ ] **Step 3: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

---

## Task 8: 前端 — 图标按需注册

**Files:**
- Modify: `llm-gateway-ui/src/main.js`

背景:各视图已显式 import 自己用的图标;只有 `App.vue`(`Cpu`)与路由 meta 的字符串图标名(`DataLine`/`Key`/`Share`/`Money`/`Tickets`,经 `<component :is>` 解析)依赖全局注册。

- [ ] **Step 1: 替换全量注册为按需注册**

`main.js` 整体替换为:

```javascript
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 只全局注册 App.vue 品牌图标与路由 meta 里按名字引用的菜单图标;各视图自己 import 所需图标
import { Cpu, DataLine, Key, Share, Money, Tickets } from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

for (const icon of [Cpu, DataLine, Key, Share, Money, Tickets]) {
  app.component(icon.name, icon)
}

app.use(ElementPlus)
app.use(router)
app.mount('#app')
```

- [ ] **Step 2: 构建并对比体积**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功;dist 里主 JS 体积应明显小于改动前(改动前可先记录一次)。

- [ ] **Step 3: 手动冒烟**

Run dev server(`npm run dev`),确认侧边栏菜单图标、顶部品牌图标、各页面按钮图标均正常显示。

---

## Task 9: 前端 — Dashboard 上限提示 + Logs 搜索长度校验

**Files:**
- Modify: `llm-gateway-ui/src/views/Dashboard.vue:9-16`
- Modify: `llm-gateway-ui/src/views/Logs.vue:27`

- [ ] **Step 1: Dashboard 前端截断保护**

`load()` 改为(后端 stats 无分页参数,前端截断防止超大租户数拖垮渲染):

```javascript
const MAX_ROWS = 100
const truncated = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await logApi.stats()
    truncated.value = data.length > MAX_ROWS
    rows.value = data.slice(0, MAX_ROWS)
  } finally {
    loading.value = false
  }
}
```

表格上方(`<div class="table-head">租户用量明细</div>` 内)追加提示:

```html
<div class="table-head">
  租户用量明细
  <el-tag v-if="truncated" size="small" type="warning" style="margin-left:8px">仅显示 Token 用量前 {{ rows.length }} 个租户</el-tag>
</div>
```

- [ ] **Step 2: Logs 搜索长度校验**

`search()` 改为:

```javascript
function search() {
  if (query.tenant.length > 100 || query.model.length > 100) {
    ElMessage.warning('搜索条件过长(≤100 字符)')
    return
  }
  query.page = 1
  load()
}
```

并在 script 顶部 import 中加入 `ElMessage`:

```javascript
import { ElMessage } from 'element-plus'
```

- [ ] **Step 3: 构建验证**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

---

## Task 10: 最终验证

- [ ] **Step 1: 后端全量测试**

Run: `cd C:/practice/llm-gateway && ./mvnw test`
Expected: BUILD SUCCESS,所有测试通过(含新增 QuotaServiceTest 与退避测试)。

- [ ] **Step 2: 前端构建**

Run: `cd C:/practice/llm-gateway-ui && npm run build`
Expected: 构建成功。

- [ ] **Step 3: 手动冒烟(可选,需要 MySQL 与后端跑起来)**

启动后端与 `npm run dev`,过一遍:API Key / 计费 / 路由规则三页的新增、编辑、校验失败后再提交、删除;Dashboard 与日志页正常加载。
