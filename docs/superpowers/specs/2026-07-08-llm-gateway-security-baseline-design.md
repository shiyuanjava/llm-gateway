# LLM Gateway 安全基线 — 设计文档

日期:2026-07-08
范围:`C:/practice/llm-gateway`(后端)+ `C:/practice/llm-gateway-ui`(前端)
背景:网关将上生产,需满足安全/合规要求。本子项目是生产化路线图(1安全 → 2SSE → 3Token计数 → 5运维硬化 → 4SCA迁移)的第一步。

## 目标

1. `/admin/**` 全部接口需管理员登录(账号密码 + JWT)才能访问。
2. API Key 不再明文落库:SHA-256 哈希存储、服务端生成、一次性展示、前缀识别、存量自动迁移。
3. 管理面审计:登录(成功/失败)与所有写操作入库可查。

## 架构

```
前端登录页 ──POST /admin/auth/login──> AdminAuthController
                                        │ BCrypt 校验 → 签发 JWT(HS256, 2h)
浏览器存 localStorage,axios 带头 ────> AdminJwtFilter(/admin/** 除 login)
                                        │ 验签 → AdminPrincipal 入请求属性
                                        ▼
                                  AdminAuditFilter(非 GET 记审计)
                                        ▼
                                  现有各 Admin Controller(不改业务逻辑)
/v1/** 走现有 ApiKeyAuthFilter,互不干扰(路径隔离)
```

- **JWT**:HS256;密钥来自环境变量 `GATEWAY_JWT_SECRET`,未设置或长度 < 32 字符则启动失败;有效期 2 小时;过期重新登录,不做 refresh token。
- **管理员引导**:启动时 `admin_user` 表为空,则用环境变量 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 创建首个账号(BCrypt);表非空时忽略这两个变量。
- **登录防爆破**:同用户名连续失败 5 次,内存锁定 5 分钟(单实例内存即可,多实例部署时由 SCA 阶段统一)。
- **新依赖**:`jjwt`(api/impl/jackson)+ `spring-security-crypto`(仅 BCrypt);不引入 Spring Security 全家桶,与现有手写 Filter 风格一致。

## 数据模型

```sql
CREATE TABLE IF NOT EXISTS admin_user (
    id            BIGINT AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt',
    enabled       TINYINT      NOT NULL DEFAULT 1,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
);

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id         BIGINT       AUTO_INCREMENT,
    username   VARCHAR(64)  NOT NULL COMMENT '登录失败时记尝试的用户名',
    action     VARCHAR(32)  NOT NULL COMMENT 'LOGIN_OK/LOGIN_FAIL/LOGIN_LOCKED/CREATE/UPDATE/DELETE/RELOAD',
    resource   VARCHAR(255) NOT NULL COMMENT '如 api-keys/3、routing-rules/auto、auth/login',
    detail     TEXT         COMMENT '请求体摘要(脱敏后)',
    client_ip  VARCHAR(45),
    status     INT          NOT NULL COMMENT 'HTTP 响应码',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_created (created_at)
);

-- api_key 表改造(schema.sql 新装直接建新结构;存量库由迁移 Runner 处理)
-- 新列:key_hash CHAR(64) NOT NULL(SHA-256 hex,UNIQUE)
--       key_prefix VARCHAR(16) NOT NULL(如 sk-gw-a1b2)
-- 移除:api_key 明文列
```

- **Key 生成**:服务端生成 `sk-gw-` + 32 位随机 hex(SecureRandom);创建接口响应返回完整 Key 仅此一次,之后任何接口不可获取全文。
- **存量迁移**:启动 Runner 检测 `api_key` 表存在明文列时:逐行计算 hash + prefix 写入新列 → 全部成功后删除明文列;幂等(新列已填充的行跳过);单行失败记 ERROR 并跳过,不阻断启动(该 Key 无法再认证,管理员重建)。
- **审计脱敏**:detail 中不落完整 Key(只落 prefix)、不落密码字段。

## 后端组件

| 组件 | 职责 |
|------|------|
| `auth/admin/AdminUserEntity` + Mapper | admin_user 表访问 |
| `auth/admin/AdminAuthService` | 登录校验(BCrypt)、JWT 签发/验签、防爆破锁定、引导账号创建 |
| `auth/admin/AdminAuthController` | `POST /admin/auth/login`(免鉴权)、`GET /admin/auth/me` |
| `auth/admin/AdminJwtFilter` | 拦截 `/admin/**`(放行 `/admin/auth/login`),验签失败 401 |
| `audit/AdminAuditLogEntity` + Mapper | admin_audit_log 表访问 |
| `audit/AdminAuditFilter` | JWT 过滤器之后,记录非 GET 请求(用户/动作/资源/IP/状态码/脱敏 body) |
| `audit/AuditAdminController`(新建) | `GET /admin/audit-logs` 分页查询(时间/用户/动作筛选) |
| `auth/ApiKeyGenerator` | 生成 `sk-gw-` Key 与 SHA-256/prefix 计算 |
| `ApiKeyService` 改造 | authenticate 按 `SHA-256(key)` 查 `key_hash` |
| `ApiKeyAdminController/Service` 改造 | create 服务端生成并一次性返回;list 返回 prefix;update 不接受 key 字段 |
| `migration/ApiKeyHashMigrationRunner` | 存量明文 Key 迁移 |

## 前端组件(llm-gateway-ui)

- `views/Login.vue`:用户名 + 密码 → login 接口 → `{ token, username, expiresAt }` 存 localStorage。
- `api/http.js`:请求拦截器带 `Authorization: Bearer <jwt>`;401 响应清 token 跳登录页(记录原路径,登录后跳回)。
- `router/index.js`:全局守卫,除 `/login` 外无 token 一律重定向登录页。
- `App.vue`:顶栏显示用户名 + 退出按钮;`/login` 路由不渲染侧边栏布局。
- `views/ApiKeys.vue`:列表显示 `keyPrefix`;新建表单去掉 Key 输入;创建成功弹一次性完整 Key 对话框(复制按钮 + "关闭后无法再查看"警告);编辑表单不含 Key。
- `views/AuditLogs.vue`(新增):审计分页查询,侧边菜单入口。

## 错误处理

| 场景 | 行为 |
|------|------|
| 未带/无效/过期 JWT 访问 `/admin/**` | 401 + R 包装 `{code:401, msg:"未登录或登录已过期"}`,前端拦截器跳登录 |
| 登录用户名或密码错误 | 401,审计 `LOGIN_FAIL`;不区分用户不存在/密码错(防枚举) |
| 连续失败 5 次 | 423 锁定 5 分钟,审计 `LOGIN_LOCKED` |
| `GATEWAY_JWT_SECRET` 缺失或 < 32 字符 | 应用启动失败,日志明确提示 |
| 迁移单行失败 | ERROR 日志 + 跳过,不阻断启动 |
| `/v1/**` | 行为不变(仍 API Key Bearer,查表改为哈希比对) |
| 审计写入失败 | WARN 日志,不影响业务响应(与现有 persistError 同思路) |

## 测试

- 单元:`AdminAuthServiceTest`(BCrypt、JWT 往返/过期、锁定)、`ApiKeyServiceTest`(哈希查找)、`ApiKeyGeneratorTest`(格式/前缀)、迁移幂等测试、`AdminAuditServiceTest`(脱敏)。
- 集成(MockMvc):无 token → 401;登录 → 带 token → 200;写操作后审计表有记录;`/admin/auth/login` 免鉴权可达。
- 前端:`npm run build` + 手动冒烟(登录、401 跳转、一次性 Key 弹窗、审计页)。

## 明确不做

多管理员角色分级、refresh token、SSO/LDAP、密码找回/修改密码接口、Key 轮换提醒、多实例共享登录锁定状态。

## 部署注意

- 上线需配置环境变量:`GATEWAY_JWT_SECRET`(≥32 字符随机串)、`ADMIN_USERNAME`、`ADMIN_PASSWORD`(首次启动后可移除)。
- 迁移会**删除** `api_key` 明文列——上线前备份该表;迁移后所有既有 Key 继续可用(哈希比对),但无法再从库中读出明文。
