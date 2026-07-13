# LLM Gateway

一个**生产级 LLM API 网关**:对外提供 OpenAI 兼容协议(含 SSE 流式),对内统一管理多供应商路由、鉴权、限流配额、内容护栏、缓存与 Token 级计费,并配套 Vue 3 管理控制台。单机可用 Docker Compose 一条命令拉起全套。

> 后端 Spring Boot 4.1 / Java 21(MVC + 虚拟线程,不引入 WebFlux);前端 Vue 3 + Element Plus + Vite;存储 MySQL 8。

## 仓库结构

| 目录 | 说明 |
|---|---|
| [`llm-gateway/`](llm-gateway/) | 网关后端(Spring Boot)。含 Dockerfile、docker-compose.yml、.env.example |
| [`llm-gateway-ui/`](llm-gateway-ui/) | 管理控制台前端(Vue 3)。含 Dockerfile、nginx.conf(同源反代) |
| [`docs/superpowers/`](docs/superpowers/) | 各子项目的设计文档(specs)与实施计划(plans),记录完整工程演进 |

## 核心能力

- **OpenAI 兼容 API**:`POST /v1/chat/completions`,支持非流式与 SSE 流式(虚拟线程阻塞直写、响应头懒提交、首帧前可重试换目标)
- **鉴权与安全**:API Key SHA-256 哈希存储(`sk-gw-` 前缀、一次性展示);管理端 JWT 账号密码登录(BCrypt + 登录防爆破锁定);操作审计落库
- **多供应商路由**:别名 → 首选/升级/降级链(DeepSeek / OpenAI / Anthropic / Mock),重试 + 熔断 + Fallback
- **治理**:令牌桶限流、租户 Token 配额、敏感词/提示注入护栏(流式增量截断)
- **缓存**:精确匹配缓存,后端可切换(内存单机 / Redis 跨重启多实例共享,compose 默认 Redis;语义缓存预留),命中直接回放(含 SSE 回放)
- **精确计费**:优先采信上游 usage,jtokkit 估算兜底;缓存读写单价四段计价;定价缺失 fail-close(422 拒绝,不打上游)
- **可观测**:traceId 全链路贯穿(请求头 `X-Request-Id` ↔ 日志 MDC ↔ `request_log.request_id` 同 ID);Prometheus 指标(QPS/错误/Token/成本/延迟/TTFT)在独立管理端口;结构化访问日志 + 滚动文件
- **管理台**:概览(按租户用量/上游成本/缓存命中)、API Key、路由规则、计费单价、请求日志与操作审计(时间范围筛选)、Playground 流式试运行

## 快速开始(Docker Compose,单机生产)

前置:Docker 20+ 与 Docker Compose v2。

```bash
cd llm-gateway
cp .env.example .env     # 编辑必填项:MYSQL_ROOT_PASSWORD、GATEWAY_JWT_SECRET(≥32 字符)、ADMIN_USERNAME/PASSWORD
docker compose up -d --build
```

| 入口 | 地址 |
|---|---|
| 管理台 | `http://localhost:8081`(nginx 同源反代 `/admin`、`/v1`,浏览器零跨域) |
| API 调用 | `http://localhost:8081/v1/chat/completions`(Bearer API Key,SSE 不缓冲) |
| Actuator/Prometheus | 容器网络内 `gateway:9090`(刻意不映射宿主机) |

首启自动建库灌种子数据;`docker compose stop` 优雅停机(进行中的请求含 SSE 流最多 30s 收尾)。详见 [`llm-gateway/README.md`](llm-gateway/README.md) 的部署章节。

## 本地开发

```bash
# 后端(需 JDK 21 + 本地 MySQL 8,建空库 llm_gateway 即可,启动自动执行 Flyway 迁移)
cd llm-gateway && mvn spring-boot:run     # 业务 8080,管理端口 9090

# 前端(Vite dev server,/admin、/v1 代理到 8080)
cd llm-gateway-ui && npm install && npm run dev
```

测试:`cd llm-gateway && mvn test`(146 项,含真库集成测试)。

## 工程演进(docs/superpowers)

项目按子项目迭代,每个子项目走「设计 spec → 实施 plan → TDD 实现 → 审查」流程,文档均在 [`docs/superpowers/`](docs/superpowers/):

1. **速赢包**(2026-07-07):超时/退避/配额缓存/N+1 等基础修复
2. **安全基线**(2026-07-08):admin JWT、API Key 哈希化、操作审计
3. **SSE 流式**(2026-07-08):MVC+虚拟线程流式、增量护栏、流式容错、TTFT
4. **精确 Token 计数**(2026-07-10):上游 usage 采信 + jtokkit 兜底、缓存 token 归一化、定价 fail-close
5. **生产运维硬化**(2026-07-11):Actuator 端口分离、CORS 收敛、traceId、优雅停机、Docker 交付与历史 backlog 清理

Roadmap 下一步:Spring Cloud Alibaba 迁移(Nacos 配置中心、Sentinel 限流熔断替换自研组件)。

## 关键端口与环境变量

| 变量 | 说明 |
|---|---|
| `GATEWAY_JWT_SECRET` | JWT 签名密钥,**≥32 字符**,缺失拒绝启动 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | 首启引导管理员(仅 admin_user 表为空时创建) |
| `DEEPSEEK_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` | 供应商密钥,留空则该供应商不可用自动 Fallback |
| `GATEWAY_MANAGEMENT_PORT` | Actuator 管理端口,默认 9090 |
| `GATEWAY_ADMIN_ALLOWED_ORIGINS` | 管理端 CORS 白名单(同源反代部署留空) |

> 注意:seed 数据含演示 Key `sk-demo-tenant-a/b` 与默认演示定价,正式对外部署前请在管理台删除/更换。
