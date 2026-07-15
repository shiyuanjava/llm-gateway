# LLM Gateway 控制台 (llm-gateway-ui)

LLM Gateway 的 Web 管理后台:**配置管理**(API Key / 路由规则 / 计费单价)+ **日志查询**(请求审计、用量、成本)。

技术栈:**Vue 3 + Vite + Element Plus + axios + vue-router**。UI 遵循 ui-ux-pro-max 的设计原则:语义化色彩令牌、矢量图标(Element Plus Icons,无 emoji)、16px 基准字号 + 数据列等宽数字、可排序/带空态与加载态的表格、带必填校验的表单、删除前二次确认。

## 页面

| 路由         | 页面     | 说明                                                       |
| ------------ | -------- | ---------------------------------------------------------- |
| `/dashboard` | 概览     | 总请求/Token/成本指标卡 + 按租户用量明细(Token 占比进度条) |
| `/api-keys`  | API Key  | 增删改查;改动后端自动 reload 鉴权缓存                      |
| `/routing`   | 路由规则 | 别名 + 首选 + 升级阈值 + **降级链**(可视化编辑)            |
| `/pricing`   | 计费单价 | 各模型每 1K Token 单价                                     |
| `/logs`      | 请求日志 | 分页 + 按租户/状态/模型筛选                                |

## 运行

先确保后端网关已在 `http://localhost:8080` 启动(见 ../llm-gateway)。

```bash
npm install
npm run dev      # 开发服务器 http://localhost:5173
# 或
npm run build    # 产物输出到 dist/
```

开发服务器通过 Vite proxy 把 `/admin` 与 `/v1` 转发到后端 `:8080`(见 `vite.config.js`),后端也对 `/admin/**` 开启了 CORS 作兜底。

## 生产部署

本仓库自带 `Dockerfile` + `nginx.conf`:nginx 托管 `dist/` 并把 `/admin`、`/v1` 同源反代到后端容器(SPA 路由回退、SSE 不缓冲)。**由后端仓库 `llm-gateway/docker-compose.yml` 统一编排**,无需单独部署(先按 `llm-gateway/README` 的部署章节配置好 `.env`):

```bash
cd ../llm-gateway && docker compose up -d --build
```

同源反代下 `VITE_API_BASE` 保持留空(相对路径);仅分域部署时才需要设置它,并在后端配 `GATEWAY_ADMIN_ALLOWED_ORIGINS` 白名单。

## 对接的后端接口(均为 `{code,msg,data}` 包装,camelCase)

- `GET/POST/PUT/DELETE /admin/api-keys`
- `GET/POST/PUT/DELETE /admin/routing-rules`(含降级链)
- `GET/POST/PUT/DELETE /admin/pricing`
- `GET /admin/logs`(分页/筛选)、`GET /admin/logs/stats`(按租户聚合)
- `GET /admin/meta`、`POST /admin/meta/reload`

> `/admin/**`(除登录接口)一律要求管理员 JWT:登录页获取 token,axios 拦截器自动携带、401 跳回登录;生产 CORS 默认收敛为空白名单(同源反代零跨域)。
