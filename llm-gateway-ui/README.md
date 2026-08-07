# LLM Gateway 控制台 (llm-gateway-ui)

LLM Gateway 的 Web 管理后台:**配置管理**(API Key / 路由规则 / 计费单价)+ **日志查询**(请求审计、用量、成本)。

技术栈:**Vue 3 + Vite + Element Plus + axios + vue-router**。界面采用“工业信号台”视觉系统：近黑背景、实验性编号排版、鼠标光场、物理感过渡和数据驱动的遥测层。所有显式界面图标统一使用 Lucide，界面文案不使用表情符号；表格、空态、加载态和表单仍保留完整的可访问交互。

## 页面

| 路由         | 页面     | 说明                                                       |
| ------------ | -------- | ---------------------------------------------------------- |
| `/dashboard` | 概览     | 遥测信号总览 + 总请求/Token/成本指标 + 租户排序 |
| `/api-keys`  | API Key  | 增删改查;改动后端自动 reload 鉴权缓存                      |
| `/routing`   | 路由规则 | 别名 + 首选 + 升级阈值 + **降级链**(可视化编辑)            |
| `/pricing`   | 计费单价 | 各模型每 1K Token 单价                                     |
| `/logs`      | 请求日志 | 分页 + 按租户/状态/模型筛选                                |
| `/audit`     | 操作审计 | 管理面登录与写操作的不可变事件流                           |
| `/ip-control`| IP 防护  | 自动频率封禁规则、白名单、手动封禁与解封                     |
| `/playground`| 试运行   | API Key 直连 `/v1` 的 SSE 流式验证                         |

## 运行

先确保后端网关已在 `http://localhost:8080` 启动(见 ../llm-gateway)。

```bash
npm install
npm run dev      # 开发服务器 http://localhost:5173
# 或
npm run build    # 产物输出到 dist/
```

开发服务器通过 Vite proxy 把 `/admin` 与 `/v1` 转发到同一个后端(默认 `http://localhost:8080`)。目标、端口和超时都可在 `.env` 中配置,且开发端口固定失败而不会静默漂移到 5174。

## 生产部署

本目录自带 `Dockerfile` + `nginx.conf`:nginx 托管 `dist/` 并把 `/admin`、`/v1` 同源反代到后端容器(SPA 路由回退、SSE 不缓冲)。**由后端 `../llm-gateway/docker-compose.yml` 统一编排**,无需单独部署(先按 `llm-gateway/README` 的部署章节配置好 `.env`):

```bash
cd ../llm-gateway && docker compose up -d --build
```

同源反代下 `VITE_API_BASE` 保持留空(相对路径)。分域部署时在构建镜像前设置 `VITE_API_BASE`,并在后端配置 `GATEWAY_CORS_ALLOWED_ORIGINS`;该白名单同时覆盖管理接口与 Playground 的 `/v1` 流式请求。前端会为两类请求统一生成 `X-Request-Id`,后端响应头、应用日志与 `request_log` 可按同一 ID 排障。

完整配置项见 `.env.example`;Docker 构建支持同名 `--build-arg`,并会从绝对 `VITE_API_BASE` 自动提取 API Origin 写入 CSP `connect-src`。流式客户端超时 `VITE_STREAM_TIMEOUT_MS` 应略大于后端 `gateway.http.stream-max-duration-ms`。

## 对接的后端接口(均为 `{code,msg,data}` 包装,camelCase)

- `GET/POST/PUT/DELETE /admin/api-keys`
- `GET/POST/PUT/DELETE /admin/routing-rules`(含降级链)
- `GET/POST/PUT/DELETE /admin/pricing`
- `GET/PUT /admin/ip-control/rule`；`GET/POST/DELETE /admin/ip-control/blocks`
- `GET /admin/logs`(分页/筛选)、`GET /admin/logs/stats`(按租户聚合)
- `GET /admin/meta`、`POST /admin/meta/reload`

> `/admin/**`(除登录接口)一律要求管理员 JWT:登录页获取 token,axios 拦截器自动携带、401 只触发一次提示并跳回登录;生产 CORS 默认收敛为空白名单(同源反代零跨域)。
