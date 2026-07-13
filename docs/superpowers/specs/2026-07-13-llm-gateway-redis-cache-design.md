# llm-gateway 子项目:响应缓存 Redis 化 设计文档

- 日期:2026-07-13
- 状态:已确认(用户逐节确认于本日)
- 范围:llm-gateway(Spring Boot 4.1 / Java 21,MVC + 虚拟线程)后端与 docker-compose;前端无改动
- 前序:子项目 1 安全基线、2 SSE 流式、3 精确 Token 计数、5 生产运维硬化均已完成;本子项目独立于子项目 4(SCA 迁移)之外——缓存不在 Sentinel/Nacos 的替换范围内,是长期资产

## 1. 目标与约束

把精确匹配响应缓存从单机内存(`ConcurrentHashMap`)升级为可选的 Redis 后端,使缓存跨重启、跨实例生效;顺带解决 Token 计数子项目遗留的 backlog「Usage 序列化往返丢缓存拆分」。

约束(与既定方向一致):

- 自研组件最小增量:只动 cache 包与配置/编排,`CacheService`、`GatewayService` 零改动;
- 不碰限流/配额:`TokenBucketRateLimiter` 与 `QuotaService` 维持现状(限流将来由 Sentinel 替换,给它上 Redis 属于加深将被替换的自研投入);
- `SemanticCache`/`MockEmbedder` 维持内存实现(预留的演示功能,本轮不碰);
- Redis 为**可选依赖**:默认内存实现,行为与今天完全一致;本地开发、现有 146 个测试均不需要 Redis。

用户决策记录:范围 = 只做响应缓存 Redis 化;部署形态 = 可选依赖(内存默认、配置开关切换);实现 = 方案 A(Spring Data Redis + 手写 `RedisResponseCache`,而非 Spring Cache 注解抽象或裸 Lettuce)。

## 2. 架构与组件

新增配置项 `gateway.cache.store`,取值 `memory`(默认)/ `redis`:

- `ExactMatchCache`(现有内存实现)加 `@ConditionalOnProperty(name = "gateway.cache.store", havingValue = "memory", matchIfMissing = true)`;
- 新增 `RedisResponseCache implements ResponseCache`,`havingValue = "redis"` 时生效;两实现互斥,`CacheService` 注入的仍是 `ResponseCache` 接口;
- pom 加 `spring-boot-starter-data-redis`(Lettuce);连接参数走 Spring Boot 标准 `spring.data.redis.host/port/timeout`,prod profile 由环境变量 `REDIS_HOST`/`REDIS_PORT` 覆盖;
- Redis key:`gw:cache:exact:<CacheKey>`;TTL 复用现有 `gateway.cache.ttl-seconds`,写入用 `SET key value EX ttl`(原子带过期,无需清理任务);
- `GatewayProperties.cache()` 加 `store` 字段。

## 3. 序列化与 Usage 拆分(清 backlog)

`Usage` 的 `cacheReadTokens`/`cacheCreationTokens` 是 `@JsonIgnore`(对外协议只出三字段),直接 JSON 序列化 `ChatCompletionResponse` 会在往返后丢掉拆分。解决:存储值包一层信封 record(cache 包内):

```java
record CachedResponse(
    ChatCompletionResponse response,   // 正常 Jackson 序列化,Usage 出三字段
    int cacheReadTokens,               // 从 response.usage() 摘出,平铺存储
    int cacheCreationTokens)
```

- 写入:从 `Usage` 摘出两个拆分字段平铺到信封;
- 读取:反序列化得三字段 `Usage` 后,用 `Usage.of(prompt, completion, cacheRead, cacheCreation)` 重建完整对象(response.usage() 为 null 时信封字段存 0、读取不重建);
- 对外协议序列化行为零改动;
- 反序列化失败(坏数据、未来字段变更)一律当未命中,记 WARN,靠 TTL 自然淘汰,不引入版本号字段;
- ObjectMapper 注入 Spring 管理的实例(与线上协议语义一致)。

## 4. 故障处理(fail-open)

缓存是加速器不是依赖,Redis 任何故障不影响请求主链路:

- `get` 抛异常(连接拒绝、超时、反序列化失败)→ WARN + 返回 `Optional.empty()`,请求照常打上游;
- `put` 抛异常 → WARN 吞掉,本次不缓存;
- `spring.data.redis.timeout: 1s`,防 Redis 挂死时请求干等;
- `management.health.redis.enabled: false`:starter 自动注册的 Redis health indicator 会在 Redis 挂时把 `/actuator/health` 整体拖成 DOWN,导致 compose 误判 gateway 不健康,与 fail-open 语义矛盾,故显式关闭;Redis 状态靠 WARN 日志观察。

## 5. Docker 编排

compose 新增第四个服务:

```yaml
redis:
  image: redis:7-alpine
  # 纯缓存:不持久化,内存上限 + LRU 淘汰
  command: ["redis-server", "--save", "", "--appendonly", "no",
            "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
  restart: unless-stopped
  # 刻意不映射宿主机端口,仅容器网络内可达(与 gateway 9090 同一策略)
```

- gateway 服务:环境变量加 `GATEWAY_CACHE_STORE: redis`、`REDIS_HOST: redis`、`REDIS_PORT: "6379"`;`depends_on` 加 redis(`service_healthy`);
- 不设 Redis 密码:未映射宿主机端口、仅容器网络内可达、不存敏感持久数据(MySQL 带密码是因为有数据卷);
- `.env.example` 不新增必填项,部署体验不变。

## 6. 配置汇总

| 配置 | 默认(application.yml) | prod profile |
|---|---|---|
| `gateway.cache.store` | `memory` | `${GATEWAY_CACHE_STORE:memory}`(compose 里设 `redis`,可一键切回) |
| `spring.data.redis.host/port` | `localhost:6379`(store=memory 时不连接) | `${REDIS_HOST:localhost}` / `${REDIS_PORT:6379}` |
| `spring.data.redis.timeout` | `1s` | 同左 |
| `management.health.redis.enabled` | `false` | 同左 |

## 7. 测试与验收

单元/装配测试(不引入 Testcontainers,维持「测试不依赖外部容器」的约定):

- `RedisResponseCache`:mock `StringRedisTemplate`,验证 key 格式、TTL 传参、信封 JSON 往返(重点:Usage 拆分字段不丢)、get/put 抛异常时 fail-open;
- 条件装配:`store=memory`(及不配置)装配 `ExactMatchCache`;`store=redis` 装配 `RedisResponseCache`。

冒烟(实机,compose):

1. `docker compose up -d --build`,四服务全部健康;
2. 同一问题请求两次,第二次日志/管理台出 `cache_hit`;
3. `docker compose restart gateway` 后同一问题再问,**仍命中**(内存缓存做不到,证明 Redis 化生效);
4. `docker compose stop redis` 后请求正常回答且不缓存(fail-open 生效),`start redis` 后恢复缓存。

## 8. 明确排除

- 分布式限流、配额 Redis 化(见 §1 约束);
- 语义缓存 Redis 化(VectorSet/RediSearch 等,预留功能成熟后再议);
- Redis 持久化、主从/哨兵/集群(单机 compose 纯缓存场景不需要);
- 缓存主动失效/管理台清缓存接口(TTL 已覆盖需求,YAGNI)。
