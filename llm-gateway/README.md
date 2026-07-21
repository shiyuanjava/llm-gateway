# LLM Gateway

一个基于 **Spring Boot 4.1 / Java 21 / MyBatis-Plus / MySQL** 的大模型网关(模型网关)教学实现。它是应用层与模型供应商之间的一层**控制面**:业务只发一个 OpenAI 兼容的标准请求,网关统一负责鉴权、限流、配额、内容安全、缓存、多模型路由、容错(重试 / 熔断 / Fallback)、计费与可观测。**配置(API Key、路由规则、计费单价)与记录(请求日志/用量)持久化在 MySQL 中**。

代码组织刻意遵循 **Harness Engineering** 的设计原则,两篇参考文章:

- 大模型网关详解:<https://javaguide.cn/ai/system-design/llm-gateway.html>
- 一文搞懂 Harness Engineering:<https://javaguide.cn/ai/agent/harness-engineering.html>

---

## 请求流水线(数据面 · 确定性顺序)

```
Client ──(OpenAI 兼容请求 /v1/chat/completions)──▶
  ① Auth 鉴权(API Key + 租户/RBAC)            ← ApiKeyAuthFilter（Key 来自 DB）
  ② RateLimit 限流(令牌桶,按租户)             ← TokenBucketRateLimiter
  ③ Quota 配额(累计 Token 上限,按租户)        ← QuotaService（按 request_log 聚合）
  ④ Guardrail.input 入站安全(敏感词/注入检测)  ← GuardrailEngine
  ⑤ Cache 查询(精确哈希 / 可选语义,命中即返回)← CacheService
  ⑥ Router 路由(规则:别名/Token阈值升级/前缀)← RuleBasedRouter（规则来自 DB）
  ⑦ Resilience 容错(重试 + 熔断 + Fallback 链)← ResilientExecutor
        └▶ ProviderAdapter(DeepSeek / OpenAI / Anthropic / Mock)── 真实 HTTP
  ⑧ Guardrail.output 出站审核                   ← GuardrailEngine
  ⑨ Cache 写入                                  ← CacheService
  ⑩ Observability(计费 + 指标 + 落库审计 + 日志)← CostCalculator / MetricsRecorder / request_log
◀── 统一响应(OpenAI 兼容)
```

编排逻辑集中在 `core/GatewayService`,每一步都委托给单一职责组件,便于独立理解、替换与测试。

---

## 持久化(MySQL + MyBatis-Plus)

| 表 | 类型 | 说明 |
|---|---|---|
| `api_key` | 配置 | API Key → 租户 / 角色 / 可用模型 |
| `routing_rule` | 配置 | 路由规则:别名 → 首选 + 升级阈值 |
| `routing_fallback` | 配置 | 路由降级链 |
| `model_pricing` | 配置 | 模型每 1K Token 计费单价 |
| `request_log` | 记录 | 每次请求的审计、用量、成本(也是配额数据源) |

- 建表与种子脚本:`src/main/resources/db/migration/`(Flyway 版本化:`V1__baseline_schema.sql` 建表、`V2__seed_demo_data.sql` 演示数据、`V3__purge_demo_api_keys.sql` 演示 Key 生产门禁;启动自动迁移,已发布脚本永不修改,变更加新版本)。
- ORM:**MyBatis-Plus**(Boot 4 须用 `mybatis-plus-spring-boot4-starter`,本项目用 `3.5.16`)。实体 `persistence/entity`、Mapper `persistence/mapper`、领域仓储 `persistence/repository`(接口 + 实现,屏蔽存储细节、便于单测注入假实现)。
- 配置类服务(`ApiKeyService` / `RuleBasedRouter` / `CostCalculator`)启动时从 DB 加载并缓存;`QuotaService` 按 `request_log` 实时聚合;`GatewayService` 在每次请求收尾时写入一条 `request_log`(success / cache_hit / error)。

---

## 管理后台与 Admin API

后端提供 `/admin/**` 管理接口(除 `/admin/auth/login` 外一律要求 Bearer JWT:`AdminJwtFilter` 无条件挂载、fail-closed,`GATEWAY_JWT_SECRET` 缺失或过短则拒绝启动;响应为 `{code,msg,data}` camelCase 包装):

| 接口 | 说明 |
|---|---|
| `GET/POST/PUT/DELETE /admin/api-keys` | API Key 增删改查 |
| `GET/POST/PUT/DELETE /admin/routing-rules` | 路由规则(含降级链)增删改查 |
| `GET/POST/PUT/DELETE /admin/pricing` | 计费单价增删改查 |
| `GET /admin/logs`、`/admin/logs/stats` | 请求日志分页查询 + 按租户用量/成本统计 |
| `GET /admin/meta`、`POST /admin/meta/reload` | 元信息(供应商/默认 LLM)+ 手动刷新配置 |

配置类接口在写操作后会自动调用 `ConfigRefreshService.reloadAll()` 热刷新 `ApiKeyService`/
`RuleBasedRouter`/`CostCalculator` 的缓存,**改完即时生效,无需重启**。

> 注意:Jackson 全局为 camelCase(管理端与前端一致);OpenAI/供应商 DTO 用 `@JsonProperty`
> 单独固定 snake_case(`max_tokens`/`prompt_tokens`/`finish_reason` 等),两套约定互不干扰。

配套 Web 后台见同级目录 **`../llm-gateway-ui`**(Vue 3 + Element Plus):配置管理 + 日志查询。

---

## Harness Engineering 六层映射

| Harness 层 | 含义 | 本项目落地 |
|---|---|---|
| L1 边界 | 该做什么、能做什么 | `api_key` 表 + `ApiKeyAuthFilter` / RBAC、`GatewayProperties` |
| L2 工具 | 可用的能力(最小集) | `LlmProvider` 适配器(DeepSeek / OpenAI / Anthropic / Mock) |
| L3 流程 | 标准操作流程 | `GatewayService` 固定顺序流水线 + `RuleBasedRouter`(规则在 DB) |
| L4 状态 | 记账与记忆 | `request_log` 表 / `CacheService` / `MetricsRecorder` |
| L5 验证 | 质检 | `GuardrailEngine`(确定性护栏)+ 单元测试 |
| L6 恢复 | 红线与应急 | `ResilientExecutor`(重试/熔断/Fallback)+ `GlobalExceptionHandler` |

设计原则:**约束优先**(策略外置到配置与数据库)、**可验证性**(确定性护栏 + 覆盖全链路的单元/集成测试)、**故障假设**(每类失败建模为带状态码的异常并可降级 + 落审计)、**少即是多**(手写轻量熔断器,不引 Resilience4j)。

---

## 模块一览

```
com.llm.gateway
├── api            统一接入:Controller + OpenAI 兼容 DTO
├── auth           鉴权:API Key 过滤器、租户主体、RBAC（Key 来自 DB）
├── ratelimit      限流(令牌桶)+ 配额(按 request_log 聚合)
├── guardrail      内容安全护栏:敏感词、提示词注入检测
├── cache          精确缓存 + 语义缓存(可插拔 Embedder)
├── router         规则路由(规则来自 DB):别名、Token 阈值升级、前缀推断
├── provider       供应商适配器:OpenAI 兼容(OpenAI/DeepSeek)、Anthropic、Mock
├── resilience     重试 + 熔断器 + Fallback 执行器
├── observability  成本计算(单价来自 DB)+ Micrometer 指标
├── persistence    MyBatis-Plus 实体 / Mapper / 仓储(配置与记录入库)
├── core           核心编排(GatewayService)+ 请求上下文
├── exception      统一异常体系 + 全局异常处理
└── config         GatewayProperties 运营参数外置配置
```

---

## 运行

需要 **JDK 21** 与一个 **MySQL 8**。

### 1. 初始化数据库

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS llm_gateway DEFAULT CHARACTER SET utf8mb4;"
```

只需建空库——启动时 Flyway 自动建表并灌入种子数据(脚本在 `src/main/resources/db/migration/`)。

数据库连接默认走环境变量(见 `application.yaml`,缺省值:localhost:3306 / root / 123456 / llm_gateway):

```bash
export MYSQL_HOST=localhost MYSQL_PORT=3306 MYSQL_DB=llm_gateway
export MYSQL_USER=root MYSQL_PASSWORD=123456
```

### 2. 配置默认 LLM 与密钥

```bash
# 可切换组件:provider 决定用哪个适配器,model 决定具体模型
export LLM_PROVIDER=deepseek
export LLM_MODEL=deepseek-v4-pro
export DEEPSEEK_API_KEY=sk-xxxx
# 可选其它供应商
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
```

### 3. 跑测试 / 启动

```bash
mvn test            # 全量测试(无需外网;集成测试需要上面配置的本地 MySQL)

# 启动前必填:管理端 JWT 密钥(≥32 字符,缺失或过短拒绝启动)与首启引导管理员
export GATEWAY_JWT_SECRET=$(openssl rand -hex 32)
export ADMIN_USERNAME=admin ADMIN_PASSWORD=change-me   # 仅 admin_user 表为空时创建

mvn spring-boot:run # 启动网关，监听 8080
```

### 4. 调用示例

```bash
curl http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-demo-tenant-a" \
  -H "Content-Type: application/json" \
  -d '{"model":"default","messages":[{"role":"user","content":"用一句话介绍你自己"}],"max_tokens":600}'
```

- `model` 可填:`default`(走 `LLM_PROVIDER`/`LLM_MODEL` 指定的默认 LLM)、**别名**(`auto`/`cheap`/`smart`,规则在 DB)、或**物理模型**(`deepseek-v4-pro` / `gpt-4o-mini` / `claude-opus-4-8` ...,按前缀路由)。
- 未配置某供应商密钥时,该供应商调用失败会自动 Fallback 到本地 `mock`,因此**链路始终可跑通**。

可观测:Actuator 在独立管理端口 9090(`GATEWAY_MANAGEMENT_PORT` 可覆盖),主端口 8080 不暴露 `/actuator/**`;生产部署时 9090 不映射宿主机,仅容器网络内供 healthcheck 与 Prometheus 抓取(指标前缀 `llm.gateway.*`)。审计与成本查询直接读 `request_log` 表。

---

## Docker Compose 部署(单机生产)

前置:Docker 20+ 与 Docker Compose v2;前端目录 `../llm-gateway-ui` 与本目录同级(同一仓库内)。

```bash
cp .env.example .env   # 只需基础设施密码:MYSQL_ROOT_PASSWORD、REDIS_PASSWORD
docker compose up -d --build
# 首次启动:gateway 会等待应用密钥(fail-closed)。打开 Nacos 控制台 http://localhost:8850,
# 在 配置管理 → llm-gateway.yaml(nacos-init 已发布空模板)填 GATEWAY_JWT_SECRET(≥32 字符)/
# ADMIN_USERNAME/ADMIN_PASSWORD/供应商 Key 并发布,然后:
docker compose restart gateway
```

密钥分级:应用密钥在 Nacos(一处存储、控制台可改、随 `nacos-data` 卷持久化,重复部署不覆盖);`.env` 只放容器启动就要用的基础设施密码。同名环境变量若显式设置则优先(CI 测试/本地裸跑用)。

拓扑与端口:

| 服务 | 端口 | 说明 |
|---|---|---|
| ui(nginx) | 宿主机 `${UI_PORT:-8081}` | 托管管理台,`/admin`、`/v1` 同源反代到网关(浏览器零跨域,SSE 不缓冲) |
| gateway | 8080(仅容器网络) | 业务端口,不映射宿主机 |
| gateway | 9090(仅容器网络) | Actuator 管理端口:`/actuator/health`、`/actuator/metrics`、`/actuator/prometheus`,供 healthcheck 与 Prometheus 抓取 |
| mysql | 3306(仅容器网络) | 数据持久化在 named volume `mysql-data`;建表/种子由 gateway 启动时 Flyway 自动迁移 |
| redis | 6379(仅容器网络) | 响应缓存(纯缓存不持久化,LRU 256MB);gateway 对其故障 fail-open |

- API 调用:`http://<host>:${UI_PORT}/v1/chat/completions`(Bearer API Key,经 nginx 反代,SSE 不缓冲)。Key 在管理台新建(`sk-gw-` 开头);seed 的演示 Key `sk-demo-tenant-a/b` 仅存在于开发环境,prod profile 首次迁移即被 `V3__purge_demo_api_keys.sql` 清除。
- 日志:`docker compose logs -f gateway`(控制台);容器内 `/app/logs/gateway.log`(prod profile,按天 + 100MB 滚动,保留 14 天,总量 2GB;已外挂 named volume `gateway-logs`,容器重建不丢)。每行日志含 traceId,与响应头 `X-Request-Id`、`request_log.request_id` 同 ID,可互查。
- 优雅停机:`docker compose stop`(SIGTERM)后不再接新请求,进行中的请求(含 SSE 流)最多 30s 收尾。

---

## 关键设计取舍

- **统一协议**:对外 OpenAI 兼容;`OpenAiCompatibleProvider` 一份实现同时服务 **OpenAI 与 DeepSeek**(仅 base-url/密钥不同),`AnthropicProvider` 负责双向翻译。新增 OpenAI 兼容供应商 = 配置 + 一个 Bean。
- **Jackson 显式注入**:各 Provider 用 Spring 配置好的 Jackson 3 `ObjectMapper`(`SNAKE_CASE` + `non_null` + `fail-on-null-for-primitives=false`)序列化/反序列化,以 String 作 HTTP body,避免 RestClient 默认转换器导致各家响应解析失败。
- **缓存键**:`SHA-256(model + messages + temperature + top_p + max_tokens)`,采样参数与 system prompt 均纳入键。
- **熔断**:每供应商一个 `CLOSED → OPEN → HALF_OPEN` 状态机,防止对故障供应商持续打无效请求。
- **可插拔**:`LlmProvider` / `ResponseCache` / `RateLimiter` / `Embedder` 及各 `*Repository` 均为接口,可平滑替换为新供应商、Redis、向量库、真实 Embedding 模型。
- **Jackson 3**:Spring Boot 4 默认 Jackson 3(包名 `tools.jackson.*`)。
- **MyBatis-Plus on Boot 4**:必须用 `mybatis-plus-spring-boot4-starter`(≥ 3.5.16,旧版在 Spring 7 有 `factoryBeanObjectType` 启动报错)。

> 教学项目,聚焦架构与工程范式;生产化时建议补充:分布式限流/缓存(Redis)、密钥加密存储、流式响应(SSE)、更强的护栏模型(Llama Guard 等)、配置热刷新与读写分离。
