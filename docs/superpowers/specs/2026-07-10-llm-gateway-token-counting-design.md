# llm-gateway 精确 Token 计数与计费 设计文档

- **日期**：2026-07-10
- **子项目**：生产化路线图子项目 3（1 安全基线 ✅ → 2 SSE 流式 ✅ → **3 精确 Token 计数** → 5 运维硬化 → 4 SCA 迁移）
- **参考项目**：sub2api（https://github.com/Wei-Shaw/sub2api ，Go 后端订阅额度分发网关）计费实现调研结论
- **涉及项目**：`C:/practice/llm-gateway`（后端）、`C:/practice/llm-gateway-ui`（前端，均非 git 仓库）

## 1. 目标与范围

让网关「记多少 token、算多少钱」与上游供应商一致。

**做**：

1. 本地估算从「4 字符 ≈ 1 token」升级为 jtokkit 真实 BPE 分词（仅估算场景）。
2. 捕获缓存 token（Anthropic cache_creation/cache_read、OpenAI cached_tokens）并按缓存单价计费。
3. 定价缺失从「静默按 $0 计费」改为「请求前 fail-close 拒绝」；定价表支持尾部通配匹配。
4. 落库缓存明细，管理端 Pricing/Logs 页跟进展示。

**不做**（明确排除，防范围蔓延）：

- 多租户倍率体系（用户/分组/峰时倍率）、支付、余额——单租户网关用不上。
- 缓存 5m/1h 分档单价、图片 token 计价、长上下文阶梯价——当前供应商配置未用到。
- `count_tokens` 端点——独立新功能，留作 backlog。
- 下游协议扩展（`prompt_tokens_details` 等）——下游 usage 保持三字段不变。
- 用本地分词覆盖上游真实 usage——sub2api 核心经验：扣费一律以上游 usage 为准，本地估算只做兜底，否则引入对账偏差。

## 2. 核心决策记录（与用户逐条确认）

| # | 决策 | 选择 |
|---|---|---|
| 1 | 子项目方向 | A：计数准确性优先（jtokkit + 缓存 token + fail-close） |
| 2 | jtokkit 使用场景 | 仅「上游缺 usage 的兜底估算」+「请求前预检/路由阈值」；不覆盖上游 usage、不做 count_tokens 端点 |
| 3 | 定价缺失行为 | 请求前拒绝（fail-close）+ 定价表尾部通配（`mock*` → $0） |
| 4 | 下游 usage 协议 | 三字段不变；缓存明细仅进内部（request_log + 管理端） |
| 5 | 缓存 token 载体 | 扩展现有 `Usage` record，新字段「只进不出」（反序列化可读、序列化永不输出），不新建独立计费域对象 |

## 3. 统一用量模型（Usage 扩展）

`api/dto/Usage.java` 增加两个 int 字段：`cacheReadTokens`、`cacheCreationTokens`。

- **语义**：`promptTokens` 恒为「完整输入」（**含**缓存，OpenAI 语义）。下游协议、quota 扣减、`request_log.prompt_tokens` 的现有含义全部不变。两个新字段是 promptTokens 的内部拆分，满足 `cacheReadTokens + cacheCreationTokens ≤ promptTokens`。
- **只进不出**：新字段可从上游 JSON 反序列化读入，但序列化时永不输出——下游响应与流式 usage 帧保持 `prompt_tokens/completion_tokens/total_tokens` 三字段。用序列化断言测试钉死。
- **反序列化兼容**：OpenAI 形态 `prompt_tokens_details.cached_tokens` 映射到 cacheReadTokens；同时容忍字段缺失（缺 → 0）。
- 既有 `Usage.of(prompt, completion)` 工厂保留（缓存字段为 0），新增带缓存字段的工厂；所有现存调用点零改动。

## 4. Provider 归一化（差异全部收在适配器）

计费核心不感知供应商差异，每个适配器负责把上游口径翻译成第 3 节的统一口径：

| 供应商 | 上游口径 | 归一化动作 |
|---|---|---|
| Anthropic | `input_tokens` **不含**缓存；另有 `cache_creation_input_tokens`、`cache_read_input_tokens` | `promptTokens = input + cacheCreation + cacheRead`（加法），明细随行 |
| OpenAI 兼容 | `prompt_tokens` **已含**缓存；明细在 `prompt_tokens_details.cached_tokens` | promptTokens 直接用，拆出 cacheReadTokens；OpenAI 自动缓存无「缓存写」概念，cacheCreationTokens 恒 0 |
| Mock | 无缓存 | 不变 |

**Anthropic 具体改动**：

- `AnthropicResponse.AnthropicUsage` 增加 `cache_creation_input_tokens`、`cache_read_input_tokens`（Integer，可空）。
- 非流式 `chat`：按加法组装 Usage。
- 流式 `readAnthropicStream`：沿用现有「`message_start` 建基线、`message_delta` 补 output」结构，基线捕获时同时记录两个缓存字段；返回的 Usage 带明细。

**OpenAI 兼容具体改动**：`readStream` 与非流式路径拿到的 `Usage` 经反序列化已带 cacheReadTokens，无额外逻辑。

## 5. 定价：通配匹配 + fail-close

### 5.1 schema

`model_pricing` 增加两列（schema.sql 更新 + 本地库 ALTER）：

```sql
cache_read_per_1k  DOUBLE NULL COMMENT '缓存读每 1K Token 单价（美元），NULL=未配置按 input 单价计',
cache_write_per_1k DOUBLE NULL COMMENT '缓存写每 1K Token 单价（美元），NULL=未配置按 input 单价计'
```

NULL 语义 = 缓存 token 退化按 `input_per_1k` 计——未配置时行为与现状完全一致，不会少收。

### 5.2 通配匹配

`model` 列支持**尾部 `*` 通配**（如 `mock*`）。`CostCalculator` 解析顺序：

1. 精确匹配；
2. 最长前缀通配匹配（`mock-dirty-it` 命中 `mock*`；若同时存在 `mock-d*` 则取更长者）；
3. 未命中 → 无定价。

seed.sql 增加 `('mock*', 0, 0)` 行，覆盖任意 mock 系列模型（既有 `mock-small`/`mock-large` 精确行保留，无害）。

### 5.3 fail-close

`GatewayService.complete` 与 `completeStream` 在 `router.route()` 之后、调上游**之前**，校验路由决策链上**每个目标**（首选 + 全部降级目标）的物理模型都能解析到定价；任一缺失 → 抛 `PricingNotConfiguredException`（继承 `GatewayException`，错误码 `pricing_not_configured`），由现有 `GlobalExceptionHandler` 返回 4xx JSON。

- 流式场景该检查发生在首帧之前，依懒提交设计天然仍返回 JSON 错误，无 SSE 形态问题。
- 请求不会打到上游（不产生无法计费的上游成本）。
- 缓存命中路径在路由之前返回，不受影响（缓存里的响应当初已正常计费）。

## 6. 成本公式

`CostCalculator.cost(model, usage)`：

```
nonCacheInput = max(0, promptTokens − cacheReadTokens − cacheCreationTokens)
cost = nonCacheInput/1000      × input_per_1k
     + cacheReadTokens/1000     × (cache_read_per_1k  ?? input_per_1k)
     + cacheCreationTokens/1000 × (cache_write_per_1k ?? input_per_1k)
     + completionTokens/1000    × output_per_1k
```

无缓存 token 时退化为现有公式，既有账单口径零漂移。`cost()` 对「无定价」的兜底返回 0 保留（fail-close 已在请求前拦截，此处只是防御）。

## 7. jtokkit 估算器

- 新依赖：`com.knuddels:jtokkit`（纯 Java tiktoken 实现，无传递依赖）。
- `TokenEstimator` 保持现有静态 API（`estimate(String)`、`estimate(List<ChatMessage>)`），内部改为 jtokkit BPE 分词；新增模型感知重载 `estimate(model, text)` / `estimate(model, messages)`。
- **编码选择**：`gpt-3.5*` 与 legacy `gpt-4*`（非 4o/4.1）→ `CL100K_BASE`；其余（gpt-4o/4.1/o 系、claude、deepseek、mock、未知）→ `O200K_BASE`。非 OpenAI 模型没有公开 tokenizer，o200k 为最接近的通用近似——估算场景可接受。
- 消息级估算每条消息加 4 token 对话格式开销（OpenAI chat 格式经验值）。
- 无参（不带 model）的旧 API 默认 `O200K_BASE`，保证 MockProvider 等既有调用点编译不动。
- **受益调用点**：上游缺 usage 的兜底（`completeStream` 估算、`LlmProvider` 默认降级）、流式中断 `persistPartial`、路由升级阈值判断（`max_prompt_tokens`）。**不改变**：上游返回了 usage 就用上游的。

## 8. 落库与管理端

### 8.1 request_log

增加两列（schema.sql + 本地库 ALTER）：

```sql
cache_read_tokens     INT NOT NULL DEFAULT 0 COMMENT '缓存读 Token 数（prompt_tokens 的内部拆分）',
cache_creation_tokens INT NOT NULL DEFAULT 0 COMMENT '缓存写 Token 数（prompt_tokens 的内部拆分）'
```

`RequestLogRecord` 及仓储实现同步扩展；`finish()` 与 `persistPartial` 写入（估算路径缓存字段恒 0）。

### 8.2 前端

- **Pricing.vue**：表格列与新建/编辑对话框增加「缓存读单价」「缓存写单价」两个可空数字字段；模型名输入允许尾部 `*`（校验规则放宽 + placeholder 提示）。对应 admin 端 CRUD DTO/服务同步加字段。
- **Logs.vue**：Token 列在 `cacheReadTokens + cacheCreationTokens > 0` 时以 tooltip 展示明细（「缓存读 X / 缓存写 Y」）；日志查询 DTO 带上两个新字段。

## 9. 测试策略

**单元测试**：

- `Usage`：OpenAI `prompt_tokens_details.cached_tokens` 形态反序列化、字段缺失容忍、**序列化输出不含新字段**（协议回归）。
- `CostCalculator`：缓存公式（有/无缓存单价、NULL 回退 input 价）、通配解析（精确优先、最长前缀）、无定价返回 0。
- `TokenEstimator`：编码选择规则、中英文样例计数（与已知 tiktoken 结果对齐的黄金样例）、消息开销。
- `AnthropicProvider`：非流式与流式缓存字段捕获、promptTokens 加法归一化。
- `OpenAiCompatibleProvider.readStream`：usage 帧带 cached_tokens 的拆分。

**集成测试**：

- 未定价模型（如 `unpriced-model-x`，不匹配任何行）流式与非流式请求 → 4xx `pricing_not_configured`，request_log 记录错误、不打上游。
- mock 模型经 `mock*` 通配正常放行。

**回归**：后端全量测试全绿；起服务冒烟（含 SSE）；前端 `npm run build`。

## 10. 明确的实现顺序建议（供 writing-plans 参考）

1. jtokkit 依赖 + TokenEstimator 升级（独立、无 schema 依赖）。
2. Usage 扩展 + 序列化测试。
3. Provider 归一化（Anthropic → OpenAI）。
4. 定价 schema/通配/CostCalculator/fail-close。
5. request_log 落库扩展。
6. 前端 Pricing/Logs。
7. 集成测试 + 全量回归 + 冒烟。

## 自审记录

1. **占位符扫描**：无 TBD/待定项；「backlog」项均在「不做」清单中显式排除。✔
2. **内部一致性**：promptTokens 含缓存的语义在 §3/§4/§6/§8 一致（Anthropic 做加法、OpenAI 直接用、公式做减法、落库为拆分）；NULL 缓存单价回退 input 价在 §5.1/§6 一致。✔
3. **范围检查**：单一实施计划可承载（约 7 个任务量级，与 SSE 子项目相当）。✔
4. **歧义检查**：「fail-close 校验范围 = 路由链全部目标」「无参估算默认 o200k」「估算路径缓存字段恒 0」均已显式化。✔
