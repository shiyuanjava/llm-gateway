# 全量优化批次实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落实 2026-07-15 代码审查发现的全部 16 项优化(后端 12 项、前端 4 项+渐进改进)。

**Architecture:** 后端与前端目录互不重叠,分两条线执行;后端按"重试语义 → 异步审计 → 配额 → 缓存上限 → 超时/连接池 → 可观测 → 安全/部署"的依赖顺序推进;每项遵循 TDD,先补测试再改实现。

**Tech Stack:** Java 21 / Spring Boot(虚拟线程)、Caffeine、MySQL、Redis、Vue 3 + Element Plus + Vite、nginx。

---

## 后端(llm-gateway)

### Task B1: 重试语义区分可重试/不可重试错误(审查项 1 + 12)
**Files:** Modify `provider/ProviderException.java`(或新增 `NonRetryableProviderException`)、`resilience/ResilientExecutor.java:71-78,118-132`、`provider/ProviderRegistry.java:34-39`、`provider/OpenAiCompatibleProvider.java`
- [ ] 在 ProviderException 携带 HTTP 状态码 / retryable 标志;上游 4xx(除 429)、未知供应商配置错误标记为不可重试
- [ ] ResilientExecutor 对不可重试异常直接上抛、不重试、不计入熔断失败
- [ ] 先写失败测试(4xx 不重试、429/5xx 重试、配置错误不触发熔断),再实现,跑通后提交

### Task B2: 审计日志异步落库(审查项 2)
**Files:** Create `audit/AsyncRequestLogWriter.java`;Modify `core/GatewayService.java:306,334,393,424`
- [ ] 有界队列 + 后台批量 insert;队列满或写失败降级为本地 WARN 日志,不阻塞主链路
- [ ] 应用关闭时 flush(@PreDestroy);补测试(入队、批量落库、满队列降级);提交

### Task B3: 配额加固(审查项 3)
**Files:** Modify `ratelimit/QuotaService.java`、`persistence/.../RequestLogRepositoryImpl.java:48-56`;Create Flyway migration(用量汇总表或索引)
- [ ] 用原子计数(内存 AtomicLong 常驻 + 周期对账)替代 check-then-act;recordUsage 不再依赖缓存条目已存在
- [ ] `sum` 查询加时间窗与覆盖索引,或改独立汇总表;测试并提交

### Task B4: 内存结构加上限/淘汰(审查项 4)
**Files:** Modify `cache/ExactMatchCache.java`、`ratelimit/TokenBucketRateLimiter.java`、`ratelimit/QuotaService.java`、`auth/admin/AdminAuthService.java`;`pom.xml` 加 Caffeine
- [ ] 全部换 Caffeine(maximumSize + expireAfterWrite/Access),现有测试保持通过;提交

### Task B5: 流式整体超时 + 出站连接池(审查项 5、6)
**Files:** Modify `provider/RestClients.java`、`provider/OpenAiCompatibleProvider.java`
- [ ] RestClient 换基于 JDK HttpClient(连接池/keep-alive)的 request factory
- [ ] 流式读取加 wall-clock deadline(可配置,默认如 300s),超时主动断流并抛可识别异常;测试并提交

### Task B6: 可观测性补全(审查项 8)
**Files:** Modify `core/GatewayService.java`、`resilience/*`
- [ ] 新增入站总请求计数(含 401/429)作错误率分母;provider 维度指标:重试次数、fallback 触发、熔断状态、上游延迟;提交

### Task B7: 熔断/登录锁定多实例说明与可切换实现(审查项 7)
- [ ] 若工程已有 Redis 抽象则提供 Redis 版实现并按配置切换;否则在配置与文档中明示单实例限制及开关位置;提交

### Task B8: JWT 轮换支持(审查项 9)
**Files:** Modify `auth/admin/AdminAuthService.java`
- [ ] 支持配置多个密钥(当前签发密钥 + 历史验签密钥列表),实现平滑轮换;测试并提交

### Task B9: 部署安全(审查项 10、11)
**Files:** Modify `.dockerignore`、`docker-compose.yml`、`Dockerfile`
- [ ] `.dockerignore` 确认排除 `.env`;Redis 设密码(env 注入)、Nacos 开鉴权;healthcheck 去掉 curl 依赖;提交

## 前端(llm-gateway-ui)

### Task F1: nginx 增加 CSP(前端项 2)
**Files:** Modify `nginx.conf`
- [ ] `Content-Security-Policy: default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;` 并在所有含 add_header 的 location 重申;提交

### Task F2: 请求竞态防护(前端项 3)
**Files:** Modify `src/views/Logs.vue`、`AuditLogs.vue`、`Dashboard.vue`
- [ ] 递增 requestId,响应回来校验为最新才赋值;查询按钮加 `:loading` 防重复;提交

### Task F3: 列表错误态 + 重试(前端项 4、6)
**Files:** Modify 各列表视图
- [ ] 加载失败时设置 error 标志,`el-empty` 错误文案 + 重试按钮,与"暂无数据"区分;提交

### Task F4: token 存储加固(前端项 1)
**Files:** Modify `src/auth/session.js`、`src/api/http.js`
- [ ] 在不改后端契约的前提下先改 `sessionStorage` 缩短暴露窗口(HttpOnly Cookie 需后端配合,另列 follow-up);提交

### Task F5: 渐进改进(前端项 9、10、11)
**Files:** Modify `src/composables/useCrudDialog.js:39`、`src/App.vue:43`、`src/views/ApiKeys.vue`
- [ ] `structuredClone` 替换 JSON 深拷贝;username 提升为响应式共享 ref;useCrudDialog 加 `onCreated` 回调消除 ApiKeys 重复 submit;提交

## 验证
- 后端:`mvn test` 全绿
- 前端:`npm run build` 通过
