# llm-gateway-ui 侧边栏折叠与自适应优化 — 设计文档

日期:2026-07-14
状态:已确认

## 背景与目标

llm-gateway-ui(Vue 3 + Element Plus)当前存在三个问题:

1. 多处界面文案暴露数据库表名(如"request_log 表"),对使用者无意义。
2. 左侧菜单为写死的 232px,不能收起。
3. 无任何响应式处理,窄屏下侧边栏与顶栏溢出。

目标:清理文案、侧边栏支持折叠成图标栏、补齐响应式布局。不引入新依赖。

## 改动范围

仅涉及 5 个文件:`src/App.vue`、`src/styles/main.css`、`src/views/Dashboard.vue`、`src/views/Logs.vue`、`src/views/AuditLogs.vue`。

## 1. 去掉表名说明文字

- `Dashboard.vue:58`:副标题"按租户聚合的用量与上游成本(缓存命中不计成本;数据源:request_log 表)"改为"按租户聚合的用量与上游成本(缓存命中不计成本)"。
- `Logs.vue:78`:页内副标题"审计、用量与成本记录(request_log 表)"——去掉括号后与顶栏副标题(路由 meta.subtitle)完全重复,直接删除该行元素。
- `AuditLogs.vue:69`:同上,删除"管理面登录与写操作记录(admin_audit_log 表)"整行。
- 删除页内副标题后,检查两个页面的标题区布局是否需要相应调整(如仅剩标题时的间距)。

## 2. 侧边栏可折叠(图标栏模式)

交互:用户已确认采用"折叠成图标栏"方案(非拖拽调宽)。

- `App.vue` 新增 `collapsed` ref,初始值从 `localStorage`(key:`ui:sidebar-collapsed`)读取;切换时写回。
- `el-aside` 宽度:展开 232px,折叠 64px,`transition: width .2s ease`。
- `el-menu` 使用原生 `:collapse="collapsed"`,折叠态悬停图标由 Element Plus 自动显示 tooltip(菜单名)。
- 折叠态:brand 区只显示 logo 方块(隐藏文字),隐藏"默认模型"页脚。
- 侧边栏底部新增切换按钮:展开态显示"⟨⟨ 收起"(图标+文字),折叠态只显示"⟩⟩"图标。使用 `Fold` / `Expand` 图标。

## 3. 自适应

断点与行为(通过 CSS 媒体查询 + `App.vue` 中监听窗口宽度):

- **≤992px**:侧边栏自动进入折叠态。实现:`window.matchMedia('(max-width: 992px)')` 监听,进入窄屏时强制 `collapsed = true`;用户仍可手动展开(手动操作优先,但再次跨过断点时重新自动折叠)。窄屏下的自动折叠不写入 localStorage(不污染用户偏好)。
- **≤768px**(顶栏防溢出):
  - 隐藏"供应商:…"标签;
  - "刷新配置"按钮只留图标;
  - 隐藏顶栏页面副标题(header-sub);
  - 顶栏与主内容区内边距压缩(24px → 12~16px)。
- **内容区**:
  - 各页面表格容器允许横向滚动(el-table 自身可滚动,确认外层无 overflow 裁剪即可);
  - Dashboard 指标卡网格改为 `repeat(auto-fit, minmax(...))` 自动换行(以实际代码为准)。

## 错误处理 / 边界

- localStorage 不可用时(隐私模式等):折叠状态退化为仅内存态,不报错。
- 登录页(`/login`)不含侧边栏,不受影响。

## 测试 / 验证

项目无前端自动化测试。验证方式:`npm run dev` 启动后人工检查——

1. 三处表名文案已消失,Logs/AuditLogs 页面布局正常;
2. 折叠/展开切换动画正常、折叠态悬停出 tooltip、刷新页面后记住状态;
3. 窗口缩到 <992px 自动折叠、<768px 顶栏不换行不溢出;
4. 各页面窄屏下表格可横向滚动、Dashboard 卡片换行正常。
