# 设计:接入 Spring Cloud Alibaba(Nacos 配置中心/注册中心 + Sentinel 限流)

日期:2026-07-14
状态:已批准

## 背景

llm-gateway 当前为 Spring Boot 4.1.0 单体网关,Docker Compose 部署(mysql + redis + gateway + ui/nginx)。
限流为单机令牌桶(`TokenBucketRateLimiter`,每租户 RPM,阈值启动时固化),配置全部来自
`application.yaml` + 环境变量,改运营参数需重启。

## 目标

1. 运营参数(限流、配额、熔断、敏感词)集中到 Nacos,改动热生效,不重启网关。
2. 网关实例注册到 Nacos,控制台可见健康状态,为多实例做准备。
3. 限流升级为 Sentinel:按租户维度限流,规则持久化在 Nacos,支持控制台动态调整。

## 版本适配(已查证)

| 组件 | 版本 | 依据 |
|---|---|---|
| Spring Boot | 4.1.0(保持) | 项目现状 |
| spring-cloud-dependencies | 2025.1.2 | SC 2025.1.2 起兼容 Boot 4.1.0 |
| spring-cloud-alibaba-dependencies | 2025.1.0.0 | 对齐 SC 2025.1.x train,支持 Boot 4.0.x/Jackson 3 |

SCA 2025.1.0.0 官方验证到 Boot 4.0.2;与 Boot 4.1 组合属同一 train,若遇个别不兼容,
回退方案为 Boot 4.0.7 或 SCA 2025.1.0.1-SNAPSHOT。

## 方案

### 1. 依赖

pom `dependencyManagement` 引入两个 BOM;依赖加:

- `spring-cloud-starter-alibaba-nacos-config`
- `spring-cloud-starter-alibaba-nacos-discovery`
- `spring-cloud-starter-alibaba-sentinel`
- `sentinel-datasource-nacos`(规则持久化)

### 2. Nacos 配置中心

- `application.yaml` 增加 `spring.config.import: optional:nacos:llm-gateway.yaml`
  (`optional:` 保证 Nacos 不可达时用本地配置兜底启动)。
- Nacos 中 `llm-gateway.yaml`(group DEFAULT_GROUP)承载 `gateway.*` 运营参数:
  rate-limit、quota、resilience、guardrail.sensitive-words。
- `GatewayProperties` 为 record 型 `@ConfigurationProperties`,依赖 Spring Cloud
  Context 的 rebind 机制(配置变更事件后重新绑定 bean)。消费方(限流器、熔断器、护栏)
  不得在构造器里固化数值,改为每次使用时从 properties bean 读取。

### 3. Nacos 服务发现

- starter 自动注册,服务名 `llm-gateway`,注册 IP:8080。
- 优雅停机自动注销(starter 默认行为)。

### 4. Sentinel 限流

- 资源:`/v1/chat/completions` 入口(Sentinel Web 拦截器自动埋点 + 热点参数规则,
  租户 ID 作为参数索引 0,实现每租户 QPS 限流)。
- 规则源:`sentinel-datasource-nacos`,dataId `llm-gateway-flow-rules` /
  `llm-gateway-param-flow-rules`,控制台或直接改 Nacos 即动态生效、重启不丢。
- 超限处理:`BlockException` 统一转 `RateLimitExceededException` → 现有 429 响应链。
- 现有 `TokenBucketRateLimiter` 保留,`gateway.rate-limit.store: memory|sentinel`
  开关选择(默认 sentinel;memory 供无 Nacos 的本地开发)。
- Sentinel Dashboard 部署在 compose,网关通过 `transport.dashboard` 上报。

### 5. Docker Compose

新增服务:

- `nacos`:`nacos/nacos-server`,standalone 模式(内嵌 derby),端口 8848(控制台)、
  9848(gRPC),healthcheck 后 gateway 才启动。
- `sentinel-dashboard`:社区镜像,端口 8858。
- gateway 环境变量:`NACOS_SERVER_ADDR=nacos:8848`、`SENTINEL_DASHBOARD=sentinel-dashboard:8858`。

### 6. 测试

- 限流开关两实现的选择逻辑:单测(@ConditionalOnProperty)。
- Sentinel 规则→429 转换:MockMvc 集成测试,内存中注册 ParamFlowRule 触发。
- Nacos 依赖的行为(config import、注册)不做自动化测试,靠 compose 部署后手动验证。

## 不做(YAGNI)

Sentinel 集群流控、Nacos 集群/MySQL 存储、Nacos 鉴权对接、多维度(按模型/全局)限流、排队削峰。

## 验收

1. `docker compose up -d --build` 全部 healthy;Nacos 控制台可见 `llm-gateway` 实例。
2. 在 Nacos 改限流阈值,不重启网关,新阈值生效。
3. 超限请求返回 429;Sentinel Dashboard 可见流量与限流指标。
