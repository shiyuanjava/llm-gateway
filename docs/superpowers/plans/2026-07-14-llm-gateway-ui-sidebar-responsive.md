# llm-gateway-ui 侧边栏折叠与自适应优化 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清理界面上的数据库表名文案,左侧菜单支持折叠成 64px 图标栏(记住偏好、窄屏自动折叠),补齐顶栏与内容区的响应式布局。

**Architecture:** 全部为前端改动,集中在 `App.vue`(布局壳:折叠状态 + matchMedia 断点监听 + 顶栏响应式)、`main.css`(页面容器窄屏内边距)和 3 个 view(文案清理、Dashboard 网格 auto-fit)。不引入新依赖,折叠用 Element Plus `el-menu` 原生 `:collapse`。

**Tech Stack:** Vue 3 `<script setup>` + Element Plus 2.8 + Vite 5;格式化用 Prettier(`npm run format`)。

**Spec:** `docs/superpowers/specs/2026-07-14-llm-gateway-ui-sidebar-responsive-design.md`

**测试说明:** 项目无前端自动化测试基建(spec 已确认)。每个任务用 `npm run build` 做编译级校验,最后一个任务做完整的人工验证清单。所有命令都在 `llm-gateway-ui/` 目录下执行。

---

### Task 1: 移除表名说明文字

**Files:**
- Modify: `llm-gateway-ui/src/views/Dashboard.vue:57-59`
- Modify: `llm-gateway-ui/src/views/Logs.vue:75-80`
- Modify: `llm-gateway-ui/src/views/AuditLogs.vue:66-71`

- [ ] **Step 1: Dashboard.vue 副标题去掉表名**

将(注意原文是全角括号和中文分号):

```html
        <div class="page-subtitle">
          按租户聚合的用量与上游成本（缓存命中不计成本;数据源:request_log 表）
        </div>
```

改为:

```html
        <div class="page-subtitle">按租户聚合的用量与上游成本（缓存命中不计成本）</div>
```

- [ ] **Step 2: Logs.vue 删除页内副标题行**

该副标题去掉括号后与顶栏(路由 meta.subtitle"审计、用量与成本记录")完全重复,整行删除。将:

```html
      <div>
        <h2 class="page-title">请求日志</h2>
        <div class="page-subtitle">审计、用量与成本记录(request_log 表)</div>
      </div>
```

改为:

```html
      <div>
        <h2 class="page-title">请求日志</h2>
      </div>
```

- [ ] **Step 3: AuditLogs.vue 删除页内副标题行**

同理(顶栏 meta.subtitle 为"管理面登录与写操作记录"),将:

```html
      <div>
        <h2 class="page-title">操作审计</h2>
        <div class="page-subtitle">管理面登录与写操作记录(admin_audit_log 表)</div>
      </div>
```

改为:

```html
      <div>
        <h2 class="page-title">操作审计</h2>
      </div>
```

- [ ] **Step 4: 确认全项目不再有表名文案**

Run: `grep -rn "request_log\|admin_audit_log" llm-gateway-ui/src/`
Expected: 无输出(退出码 1)

- [ ] **Step 5: 构建校验**

Run: `cd llm-gateway-ui && npm run build`
Expected: `✓ built in ...`,无报错

- [ ] **Step 6: Commit**

```bash
git add llm-gateway-ui/src/views/Dashboard.vue llm-gateway-ui/src/views/Logs.vue llm-gateway-ui/src/views/AuditLogs.vue
git commit -m "chore: 去掉界面上暴露数据库表名的说明文字"
```

---

### Task 2: 侧边栏可折叠(手动切换 + 记住偏好)

**Files:**
- Modify: `llm-gateway-ui/src/App.vue`(script、template、style 三处)

- [ ] **Step 1: script 增加折叠状态**

在 `App.vue` 的 `<script setup>` 中:

图标 import 行改为(增加 `Fold`、`Expand`):

```js
import { Refresh, User, Fold, Expand } from '@element-plus/icons-vue'
```

在 `const router = useRouter()` 之后、`const isLogin = ...` 之前插入:

```js
// 侧边栏折叠:localStorage 记住手动偏好;不可用时(隐私模式)退化为内存态
const COLLAPSE_KEY = 'ui:sidebar-collapsed'

function readStoredCollapsed() {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === '1'
  } catch {
    return false
  }
}

const collapsed = ref(readStoredCollapsed())

function toggleSidebar() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
  } catch {
    /* 仅内存态 */
  }
}
```

- [ ] **Step 2: template 改造侧边栏**

将 `<el-aside width="232px" class="sidebar">` 到 `</el-aside>` 整块替换为:

```html
    <el-aside :width="collapsed ? '64px' : '232px'" class="sidebar" :class="{ 'is-collapsed': collapsed }">
      <div class="brand">
        <div class="brand-mark">
          <el-icon :size="20"><Cpu /></el-icon>
        </div>
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-name">LLM Gateway</div>
          <div class="brand-sub">控制台</div>
        </div>
      </div>

      <el-menu
        :default-active="route.path"
        router
        class="menu"
        :collapse="collapsed"
        :collapse-transition="false"
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>

      <div v-if="!collapsed" class="sidebar-foot">
        <div class="foot-label">默认模型</div>
        <div class="foot-value tabular-nums">
          {{ meta.defaultProvider }} / {{ meta.defaultModel }}
        </div>
      </div>

      <button class="collapse-toggle" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleSidebar">
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起</span>
      </button>
    </el-aside>
```

要点:`:collapse="collapsed"` 时 Element Plus 自动隐藏 `<span>` 文字并在悬停时显示 tooltip;`:collapse-transition="false"` 关掉菜单自带动画,宽度过渡由 CSS 统一做,避免两个动画打架。

- [ ] **Step 3: style 增加折叠态样式**

`.sidebar` 规则追加一行过渡:

```css
.sidebar {
  background: var(--app-sidebar-bg);
  display: flex;
  flex-direction: column;
  border-right: none;
  transition: width 0.2s ease;
  overflow: hidden;
}
```

在 `.sidebar-foot` 相关规则之后追加:

```css
/* 折叠态:brand 居中只留 logo,菜单收窄 */
.is-collapsed .brand {
  justify-content: center;
  padding: 20px 0 16px;
}
.is-collapsed .menu {
  padding: 6px 8px;
}
.menu:not(.el-menu--collapse) {
  width: 100%;
}

.collapse-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  height: 44px;
  border: none;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: transparent;
  color: var(--app-sidebar-text);
  cursor: pointer;
  font-size: 13px;
  font-family: inherit;
}
.collapse-toggle:hover {
  background: var(--app-sidebar-bg-soft);
  color: #fff;
}
```

说明:`el-menu` 在非折叠态默认有自己的宽度计算,`.menu:not(.el-menu--collapse) { width: 100% }` 保证它撑满 232px 的 aside;折叠态由 `el-menu--collapse` 自己控制(64px 减去 padding 后图标居中)。

- [ ] **Step 4: 构建校验**

Run: `cd llm-gateway-ui && npm run build`
Expected: `✓ built in ...`,无报错

- [ ] **Step 5: 人工快速验证(dev server)**

Run: `cd llm-gateway-ui && npm run dev`,浏览器打开后检查:
- 点"收起"按钮:侧边栏平滑收窄到 64px,只剩图标;悬停图标出现菜单名 tooltip
- 折叠态点"⟩⟩"按钮恢复 232px
- 刷新页面:折叠状态被记住
- 当前激活菜单项在折叠态仍有高亮背景

- [ ] **Step 6: Commit**

```bash
git add llm-gateway-ui/src/App.vue
git commit -m "feat: 侧边栏支持折叠成图标栏并记住偏好"
```

---

### Task 3: 窄屏(≤992px)自动折叠

**Files:**
- Modify: `llm-gateway-ui/src/App.vue`(仅 script)

- [ ] **Step 1: 增加 matchMedia 断点监听**

`vue` 的 import 行改为(增加 `onBeforeUnmount`):

```js
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
```

在 Task 2 添加的 `toggleSidebar` 函数之后插入:

```js
// ≤992px 自动折叠;回宽屏恢复手动偏好。自动折叠不写 localStorage,避免污染用户设置
const narrowQuery = window.matchMedia('(max-width: 992px)')

function onNarrowChange(e) {
  collapsed.value = e.matches ? true : readStoredCollapsed()
}
```

将文件已有的 `onMounted(() => { if (!isLogin.value) loadMeta() })` 改为:

```js
onMounted(() => {
  if (!isLogin.value) loadMeta()
  if (narrowQuery.matches) collapsed.value = true
  narrowQuery.addEventListener('change', onNarrowChange)
})

onBeforeUnmount(() => {
  narrowQuery.removeEventListener('change', onNarrowChange)
})
```

注:窄屏下用户仍可点按钮手动展开(`toggleSidebar` 不受影响);再次跨过断点时重新自动折叠。

- [ ] **Step 2: 构建校验**

Run: `cd llm-gateway-ui && npm run build`
Expected: `✓ built in ...`,无报错

- [ ] **Step 3: 人工快速验证**

dev server 下把窗口从宽拖窄跨过 992px:侧边栏自动折叠;拖回宽屏:恢复到手动偏好状态;窄屏下手动展开仍可用。

- [ ] **Step 4: Commit**

```bash
git add llm-gateway-ui/src/App.vue
git commit -m "feat: 窄屏下侧边栏自动折叠"
```

---

### Task 4: 顶栏与内容区响应式

**Files:**
- Modify: `llm-gateway-ui/src/App.vue`(template 顶栏 + style)
- Modify: `llm-gateway-ui/src/styles/main.css`(page 容器内边距)
- Modify: `llm-gateway-ui/src/views/Dashboard.vue`(stat-grid 改 auto-fit)

- [ ] **Step 1: App.vue 顶栏元素加响应式 class**

供应商标签加 class(`<el-tag type="info" ...>` 一行):

```html
          <el-tag type="info" effect="plain" round class="provider-tag">
            供应商：{{ meta.providers.join(' / ') }}
          </el-tag>
```

刷新按钮文字包进 span(替换原 `<el-icon><Refresh /></el-icon>&nbsp;刷新配置`):

```html
          <el-button :loading="reloading" @click="reloadConfig">
            <el-icon><Refresh /></el-icon><span class="hide-sm">&nbsp;刷新配置</span>
          </el-button>
```

- [ ] **Step 2: App.vue style 增加媒体查询**

在 scoped style 末尾(`.fade-*` 规则之前或之后均可)追加:

```css
@media (max-width: 768px) {
  .header {
    padding: 0 12px;
  }
  .header-sub,
  .provider-tag,
  .hide-sm {
    display: none;
  }
  .header-actions {
    gap: 8px;
  }
}
```

- [ ] **Step 3: main.css 页面容器窄屏收紧内边距**

在 `.page-subtitle` 规则之后追加:

```css
@media (max-width: 768px) {
  .page {
    padding: var(--space-3);
  }
  .page-header {
    margin-bottom: var(--space-4);
  }
}
```

- [ ] **Step 4: Dashboard 指标卡网格改 auto-fit**

将:

```css
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
```

替换为:

```css
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
}
```

- [ ] **Step 5: 构建校验**

Run: `cd llm-gateway-ui && npm run build`
Expected: `✓ built in ...`,无报错

- [ ] **Step 6: Commit**

```bash
git add llm-gateway-ui/src/App.vue llm-gateway-ui/src/styles/main.css llm-gateway-ui/src/views/Dashboard.vue
git commit -m "feat: 顶栏与内容区响应式布局"
```

---

### Task 5: 格式化与完整人工验证

**Files:** 无新增改动(格式化可能触碰以上文件)

- [ ] **Step 1: Prettier 格式化**

Run: `cd llm-gateway-ui && npm run format`
Expected: 输出各文件 `(unchanged)` 或列出被重排的文件

- [ ] **Step 2: 格式化校验 + 构建**

Run: `cd llm-gateway-ui && npm run format:check && npm run build`
Expected: `All matched files use Prettier code style!` 且构建成功

- [ ] **Step 3: 完整人工验证清单**

Run: `cd llm-gateway-ui && npm run dev`,按 spec 验证:

1. 概览/请求日志/操作审计三页均无"request_log 表""admin_audit_log 表"文案;请求日志与操作审计页仅剩标题、布局不塌
2. 折叠/展开切换动画正常、折叠态悬停出 tooltip、刷新页面记住状态
3. 窗口 <992px 自动折叠、回宽屏恢复偏好;<768px 顶栏不换行不溢出(供应商标签消失、刷新按钮只剩图标)
4. 窄屏下各页表格可横向滚动、Dashboard 指标卡自动换行、试运行(Playground)页无横向溢出

- [ ] **Step 4: 如格式化产生改动则提交**

```bash
git add -A llm-gateway-ui
git commit -m "style: prettier 格式化"
```

(若 `git status` 干净则跳过)
