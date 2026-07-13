# LLM Gateway 速赢优化包 — 设计文档

日期:2026-07-07
范围:`C:/practice/llm-gateway`(Spring Boot 后端)+ `C:/practice/llm-gateway-ui`(Vue 3 + Element Plus 前端)

## 背景

对前后端做了全面审查,发现若干高严重度、低修复成本的问题。本轮只做"速赢包":风险低、收益直接的修复。admin 鉴权、SSE 流式、精确 token 计数等中型项目留到后续单独设计。

## 后端改动

### 1. 超时配置
- `OpenAiCompatibleProvider` 与 `AnthropicProvider`:RestClient 当前无任何超时,供应商挂起会永久阻塞线程。改为通过 `SimpleClientHttpRequestFactory` 配置 connect 5s / read 30s;超时值加入 `GatewayProperties`(`gateway.provider.connect-timeout-ms` / `read-timeout-ms`),两个 Provider 共用。
- `application.yaml` 补 HikariCP 配置:`maximum-pool-size: 20`、`connection-timeout: 10000`、`idle-timeout: 600000`。
- 配额聚合查询(`sumTokensByTenant`)加 5s 查询超时。

### 2. 重试指数退避
`ResilientExecutor` 当前紧循环重试,会打爆故障供应商。重试之间加 `Thread.sleep(min(1000, 100 * 2^attempt))`;捕获 `InterruptedException` 时恢复中断标志并立即停止重试。

### 3. 配额检查缓存
`QuotaService.checkQuota()` 当前每请求对 `request_log` 做全表聚合。改为:
- 内存缓存 `ConcurrentHashMap<String, CachedUsage>`(tenant → 已用 tokens + 加载时间),TTL 60s,过期后重查 DB。
- 请求成功记账后在缓存上本地累加,保证 TTL 窗口内也基本准确。
- schema SQL 补 `request_log(tenant, created_at)` 索引。

### 4. N+1 查询修复
- `RoutingRuleRepositoryImpl.findAll()`:改为一次 `selectList` 查出全部 fallback,内存按 alias 分组后组装,101 次查询降为 2 次。
- `RoutingRuleAdminService.list()` 同样改批量。

## 前端改动

### 5. 表单校验卡死 + 删除错误处理(ApiKeys / Pricing / RoutingRules 三页)
- `submit()`:校验单独 try-catch,失败提前 return,不再置 `saving=true` 后卡死。
- `remove()`:API 调用包 try-catch;仅成功时提示"已删除"并刷新列表(错误提示已由 axios 拦截器统一处理)。

### 6. 抽取 `useCrudDialog` composable
新建 `src/composables/useCrudDialog.js`,统一封装:dialog 状态、openCreate/openEdit(深拷贝行数据)、submit(校验 + saving 状态 + 成功后刷新)、remove(确认 + 错误处理 + 按行 loading)。三个 CRUD 页面改用它,消除约 150 行重复,并顺带解决 #5 与删除按钮无 loading 问题。

### 7. API 地址环境变量
`src/api/http.js` 的 baseURL 改为 `import.meta.env.VITE_API_BASE || ''`;新增 `.env.example` 说明。开发模式仍走 Vite proxy(VITE_API_BASE 留空)。

### 8. Element Plus 图标按需注册
`main.js` 去掉全量图标循环注册,只显式注册各视图实际用到的图标,降低首包体积。

### 9. 小 UX 修补
- `Dashboard.vue`:stats 请求加条数上限(如 top 100),表格上方提示"仅显示前 N 条"。
- `Logs.vue`:搜索输入加长度校验(>100 字符提示并阻止)。

## 明确不做(后续单独立项)
admin 鉴权、SSE 流式支持、tiktoken 精确计数、语义缓存向量索引、配置原子热更新、请求去重、成本配额。

**技术栈方向**:网关后续计划迁移到 Spring Cloud Alibaba(Nacos 配置中心、Sentinel 限流/熔断)。因此本轮对自研限流/熔断/重试只做最小修补(退避、超时),不做深度重构——这些组件未来会被 Sentinel 替换。

## 验证
- 后端:`mvn test` 全绿、`mvn compile` 通过;新增退避/配额缓存的单元测试。
- 前端:`npm run build` 通过;手动过一遍三个 CRUD 页面(新建/编辑/删除/校验失败重试)。
