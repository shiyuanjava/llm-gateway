# llm-gateway 子项目 5:生产运维硬化 设计文档

- 日期:2026-07-11
- 状态:已确认(用户逐节确认于本日)
- 范围:llm-gateway(Spring Boot 4.1 / Java 21,MVC + 虚拟线程)与 llm-gateway-ui(Vue 3 + Element Plus + Vite)
- 前序:子项目 1 安全基线、2 SSE 流式、3 精确 Token 计数均已完成;本子项目完成后进入子项目 4(SCA 迁移)

## 1. 目标与约束

把网关从「本地可跑」推进到「单机生产可部署」:安全收口、可观测、可交付、清历史 backlog。

约束(与 SCA 终态兼容,用户既定方向):

- 保持 MVC servlet 栈,不引入 WebFlux;
- 不引入 Spring Security 过滤链(现有手写 Filter 体系不动摇),仅继续用 spring-security-crypto;
- 自研组件只做最小增量,限流/熔断/配置热更新将来由 Sentinel/Nacos 替换;
- 不引入 JSON 日志 encoder、不做 K8s manifests(本轮明确排除)。

用户决策记录:四块全做;部署形态 = Docker Compose 单机;Actuator 保护 = 管理端口分离;日志 = 文本滚动 + traceId(不加 JSON);部署拓扑 = nginx 同源反代(方案 A);backlog 除「Usage 序列化往返丢缓存拆分」(留给缓存 Redis 化)外全修。

## 2. 安全收口

### 2.1 Actuator 管理端口分离

现状:`/actuator/health|metrics|prometheus` 在主端口 8080 完全公开(`AuthFilterConfig` 两个鉴权 Filter 只挂 `/v1/*` 与 `/admin/*`)。

设计:

- `management.server.port: 9090`(环境变量 `GATEWAY_MANAGEMENT_PORT` 可覆盖);主端口 8080 上 `/actuator/**` 随端口分离自动消失。
- `management.endpoint.health.show-details: always`(仅内网可见,便于排查 db/磁盘)。
- 暴露端点维持 `health, info, metrics, prometheus`。
- docker-compose 中 9090 不映射宿主机,仅容器网络内供 healthcheck 与将来 Prometheus 抓取。
- pom 补 `micrometer-registry-prometheus`(runtime)依赖——现状配置了 prometheus 端点但缺 registry 依赖,实际 404。

### 2.2 CORS 收敛 + 401 响应缺 CORS 头(一并修复)

现状:`WebCorsConfig` 是 MVC 级(handler 阶段)CORS 且 `allowedOriginPatterns("*")`;`AdminJwtFilter`/`ApiKeyAuthFilter` 在 Servlet Filter 里直写 401,走不到 MVC CORS,跨域部署时前端拿不到 401 响应体。

设计:

- 删除 `WebCorsConfig`,新增 `CorsConfig`:`FilterRegistrationBean<CorsFilter>`(`UrlBasedCorsConfigurationSource`),映射 `/admin/**`,注册在所有鉴权 Filter 之前 → 401 直写响应也带 CORS 头,OPTIONS 预检由 CorsFilter 处理。
- 允许来源配置化:`gateway.admin.allowed-origins`(List)。默认 `http://localhost:5173` 方便开发直连;prod profile 覆盖为空列表(同源反代下浏览器不发跨域请求);确需分域部署时用环境变量 `GATEWAY_ADMIN_ALLOWED_ORIGINS`(逗号分隔)打开——它是分域部署的后备能力。
- methods:GET/POST/PUT/DELETE/OPTIONS;headers:`*`;不开 allowCredentials(前端用 Authorization 头,非 cookie)。
- `/v1/**` 不配 CORS(服务端对服务端 API,维持现状)。

### 2.3 Filter 顺序重排

现状 `ApiKeyAuthFilter` 占 `Ordered.HIGHEST_PRECEDENCE`(Integer.MIN_VALUE),前面插不进新 Filter。重排(间隔 10):

| 顺序 | Filter | 路径 |
|---|---|---|
| HIGHEST_PRECEDENCE | TraceIdFilter(新) | `/*` |
| +10 | CorsFilter(新) | `/admin/*` |
| +20 | ApiKeyAuthFilter | `/v1/*` |
| +30 | AdminJwtFilter | `/admin/*` |
| +40 | AdminAuditFilter | `/admin/*` |

## 3. 可观测性

### 3.1 TraceIdFilter + MDC 贯穿

现状:无 MDC;`GatewayContext.requestId` 自生成且仅手动拼进部分日志。

设计:

- 新增 `TraceIdFilter`(全路径,顺序最前):取请求头 `X-Request-Id`(校验:长度 ≤ 64、仅 `[A-Za-z0-9_-]`,非法则忽略,防日志注入),缺失/非法时自生成短 ID(UUID 去横线取前 16 位,与现有 `GatewayContext.requestId` 风格对齐);写入 MDC key `traceId`;响应头回写 `X-Request-Id`;`finally` 清 MDC。
- `GatewayService` 创建 `GatewayContext` 时复用 MDC 的 traceId 作为 requestId(取不到再自生成)——应用日志、响应头、`request_log.request_id` 三者同一 ID,可互查。

### 3.2 logback-spring.xml

现状:无任何日志配置。

设计(不加新依赖):

- 控制台 appender 始终开,pattern 含 `%X{traceId:-}`(docker logs / dev 都靠它)。
- 滚动文件 appender 仅 `prod` profile 启用(`<springProfile>`):`logs/gateway.log`,SizeAndTimeBasedRollingPolicy 按天 + 单文件 100MB,保留 14 天、totalSizeCap 2GB。
- 级别:root INFO;dev profile 下 `com.llm.gateway` DEBUG。

### 3.3 优雅停机

现状:无 graceful shutdown 配置;SSE 为虚拟线程阻塞直写。

设计:

- `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`。
- 行为说明(不需要新代码):停机时不再接新请求;进行中的 SSE 流 30 秒内自然完成则正常收尾;超时强停导致断流时,客户端侧表现为流中断,服务端 `SseWriter` 写失败 → `ClientDisconnectedException` → `persistPartial` 兜底落库(既有路径)。
- 验证方式:冒烟阶段 `docker compose stop`(默认 SIGTERM)观察日志出现 graceful shutdown 字样、容器在超时内退出。

## 4. 部署交付(nginx 同源反代,方案 A)

### 4.1 后端 Dockerfile(llm-gateway/)

- 多阶段:`maven:3.9-eclipse-temurin-21` 编译(`-DskipTests`,依赖层与源码层分开拷贝利用缓存)→ `eclipse-temurin:21-jre` 运行。
- Spring Boot layered jar(`layertools extract`)分层 COPY,提升重建缓存命中。
- 非 root 用户运行;`ENTRYPOINT exec java org.springframework.boot.loader.launch.JarLauncher`(exec 形式保证 SIGTERM 直达 JVM,优雅停机生效)。
- 中国网络环境注释:Dockerfile 内注释给出阿里云 Maven mirror 的可选 build-arg,默认官方源。

### 4.2 前端 Dockerfile + nginx.conf(llm-gateway-ui/)

- 多阶段:`node:20-alpine` `npm ci && npm run build` → `nginx:alpine`。
- nginx.conf:
  - 托管 `dist/`,SPA 回退 `try_files $uri $uri/ /index.html`;静态资源 gzip + 缓存头。
  - `/admin/`、`/v1/` 反代 `http://gateway:8080`(同源,浏览器零跨域)。
  - SSE 关键配置(`/v1/` 反代段):`proxy_buffering off`、`proxy_read_timeout 300s`、`proxy_http_version 1.1`,防止流式被缓冲/掐断。
- 生产构建 `VITE_API_BASE` 留空(同源相对路径),与现有 `.env.example` 默认一致。

### 4.3 docker-compose.yml(放 llm-gateway/ 下,前端 build context 相对引用 `../llm-gateway-ui`)

三服务:

- `mysql`(mysql:8):named volume 持久化;`schema.sql`、`seed.sql` 挂 `/docker-entrypoint-initdb.d/`(首启自动建库);healthcheck `mysqladmin ping`。
- `gateway`:build 后端;`depends_on: mysql: condition: service_healthy`;env 注入数据源(`jdbc:mysql://mysql:3306/...`)、`GATEWAY_JWT_SECRET`、`ADMIN_USERNAME/PASSWORD`、provider keys、`SPRING_PROFILES_ACTIVE=prod`;healthcheck 打 `http://localhost:9090/actuator/health`;8080 仅容器网络内(由 ui 反代),9090 不映射宿主机;可选挂 `logs/` volume。
- `ui`:build 前端;映射宿主机端口(默认 `8081:80`);depends_on gateway。

配 `.env.example`(compose 同目录):`MYSQL_ROOT_PASSWORD`、`GATEWAY_JWT_SECRET`、`ADMIN_USERNAME`、`ADMIN_PASSWORD`、`OPENAI_API_KEY` 等 provider keys、`GATEWAY_ADMIN_ALLOWED_ORIGINS`(默认空)。

### 4.4 prod profile 与文档

- `application-prod.yaml`:只放生产差异,当前内容明确为一项——`gateway.admin.allowed-origins` 覆盖为空列表(生产默认同源、零跨域;确需分域再用 `GATEWAY_ADMIN_ALLOWED_ORIGINS` 打开);文件日志由 logback 的 `<springProfile name="prod">` 随 profile 激活,不占该文件;其余差异一律环境变量。
- 两侧 README 增补「Docker Compose 部署」章节:一条命令拉起、必填 env、端口拓扑、日志位置、优雅停机说明。

### 4.5 冒烟降级预案

实施时先 `docker --version` 检查本机 Docker;不可用则:交付物(Dockerfile/compose/nginx.conf)照写,冒烟退化为本地 jar(prod profile)+ 前端 dev server 验证应用层行为,compose 全链路留待有 Docker 的环境执行(计划中作为显式步骤)。

## 5. 历史 backlog 修复

### 5.1 审计/请求日志时间范围筛选(安全基线遗留;前后端)

- 后端:`AuditAdminController.list` 与 `LogAdminController.list` 加可选 `from`/`to` 参数(ISO-8601 日期时间,`@DateTimeFormat(iso = DATE_TIME)` → `LocalDateTime`),对 `created_at` 做 `ge`/`le`。
- 前端:`AuditLogs.vue`、`Logs.vue` 加 `el-date-picker type="datetimerange"`,查询时格式化为 ISO 传参;清空即不筛。

### 5.2 登录响应加 expiresAt(安全基线遗留;前后端)

- 后端:`AdminAuthService` 签发处把过期时刻(epoch 毫秒)与 token 一起返回(签发与响应用同一时刻值,不二次推算);`LoginResponse` 加 `expiresAt` 字段。
- 前端:`session.js` 存 `gw_admin_expires_at`;路由守卫每次导航校验,已过期 → 清 session 跳登录(带 redirect),避免发出必 401 的请求;存量旧 session 无 expiresAt 视为有效,401 拦截器兜底不变。

### 5.3 Pricing PUT 全量更新、可清 NULL(Token 计数遗留)

- `PricingAdminController.update` 从 `updateById`(null 字段跳过)改为 UpdateWrapper 显式 `set` 全部业务列(模型名、各单价含缓存单价)→ PUT 全量更新语义,缓存单价可清回 NULL,无需删行重建。前端无需改(表单本就整行提交)。

### 5.4 缓存回放中断的审计修正(Token 计数遗留)

现状:流式 cache-hit 回放中断时走 `persistPartial(aggregatorRef)`,而回放不经过聚合器 → `served_model` 为空,且按估算 token 误计上游成本。

设计:

- cache-hit 流路径在 `replay()` 之前 `context.setServedModel(cached.model())` 并 `markCacheHit()`。
- `ClientDisconnectedException` 分支:若 `context.cacheHit()`,落库行改为 `served_model` 取上下文值、`cache_hit=true`、`cost=0`(缓存回放无上游成本)、状态仍 `client_aborted`。
- `persistPartial` 通用回退:`aggregator.model()` 为空时取 `context.servedModel()`。

### 5.5 stats 成本汇总排除 cache_hit(Token 计数遗留;前后端)

- `/admin/logs/stats`:成本列改 `SUM(CASE WHEN cache_hit THEN 0 ELSE cost_usd END)`(成本=上游真实成本口径);token 汇总保留全量(配额消耗口径);新增 `cacheHits` 计数列。
- 前端 Dashboard 统计表加「缓存命中」列。

### 5.6 明确不做(留 backlog)

- Usage 序列化往返丢缓存拆分 → 缓存 Redis 化子项目处理。
- K8s manifests、JSON 日志、Prometheus/Grafana 容器编排入 compose(仅留 9090 抓取口)。

## 6. 错误处理

无新增错误路径:CorsFilter 只加响应头;TraceIdFilter 对非法 `X-Request-Id` 静默改为自生成;`from`/`to` 格式非法由 Spring 参数转换失败返回 400(现有全局处理器覆盖);Docker healthcheck 失败表现为容器 unhealthy,不影响应用语义。

## 7. 测试与验收

- TDD 推进,新增/修改单测:
  - 401 响应带 CORS 头(MockMvc,带 Origin 头断言 `Access-Control-Allow-Origin`);
  - TraceIdFilter:MDC 设置/finally 清理/外部合法值透传/非法值替换/响应头回写;
  - 审计与请求日志 `from`/`to` 筛选(含边界:只传 from、只传 to);
  - `LoginResponse.expiresAt` 值与 token TTL 一致;
  - Pricing PUT 把缓存单价清回 NULL;
  - stats:cache_hit 行成本不计入、cacheHits 计数正确;
  - 缓存回放中断:`served_model` 非空、`cache_hit=true`、`cost=0`。
- 全量 `mvn test` 绿(现有 129 个不回归)。
- 冒烟(有 Docker):`docker compose up` → 登录 → Playground 流式 → Logs/AuditLogs 时间筛选 → 容器内 `9090/actuator/health|prometheus` 可达、宿主机 8080/9090 不可达 actuator → `docker compose stop` 观察优雅停机。无 Docker 按 4.5 降级。
- 冒烟注意(既有教训):起服务前确认 8080 无旧进程残留;本机 curl 加 `--noproxy '*'`。

## 8. 交付物清单

| 项 | 位置 |
|---|---|
| CorsConfig(CorsFilter 注册)、TraceIdFilter、Filter 顺序重排 | llm-gateway `auth`/`config` 包 |
| application.yaml 增量(management 端口、graceful、allowed-origins)+ application-prod.yaml | llm-gateway resources |
| logback-spring.xml | llm-gateway resources |
| pom:micrometer-registry-prometheus | llm-gateway pom.xml |
| backlog 后端修复 | AuditAdminController、LogAdminController、AdminAuthService/Controller、PricingAdminController、GatewayService |
| Dockerfile(后端) | llm-gateway/ |
| Dockerfile + nginx.conf(前端) | llm-gateway-ui/ |
| docker-compose.yml + .env.example | llm-gateway/ |
| 前端:时间筛选、expiresAt 会话、Dashboard 缓存命中列 | llm-gateway-ui src/views、src/auth、src/router |
| README 部署章节 | 两仓库 |
