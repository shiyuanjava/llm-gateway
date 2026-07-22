<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, User, Fold, Expand } from '@element-plus/icons-vue'
import { metaApi } from './api'
import { clearSession, currentUsername } from './auth/session'
import GatewayLogo from './components/GatewayLogo.vue'

const route = useRoute()
const router = useRouter()

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

// ≤992px 自动折叠;回宽屏恢复手动偏好。自动折叠不写 localStorage,避免污染用户设置
const narrowQuery = window.matchMedia('(max-width: 992px)')

function onNarrowChange(e) {
  collapsed.value = e.matches ? true : readStoredCollapsed()
}

const isLogin = computed(() => route.path === '/login')
// 响应式用户名:登录/登出时由 session.js 更新
const username = currentUsername

function logout() {
  clearSession()
  router.push('/login')
}

// 从路由表生成菜单
const menus = computed(() =>
  router.options.routes
    .filter((r) => r.meta && r.meta.title)
    .map((r) => ({ path: r.path, title: r.meta.title, icon: r.meta.icon }))
)

const current = computed(() => ({
  title: route.meta?.title || '',
  subtitle: route.meta?.subtitle || '',
}))

const meta = ref({ providers: [], defaultProvider: '', defaultModel: '' })
const reloading = ref(false)

async function loadMeta() {
  try {
    meta.value = await metaApi.get()
  } catch (e) {
    /* 错误已由拦截器提示 */
  }
}

async function reloadConfig() {
  reloading.value = true
  try {
    await metaApi.reload()
    ElMessage.success('配置缓存已刷新')
  } catch (e) {
    /* 错误已由拦截器提示;吞掉 rejection,点击回调可安全地 fire-and-forget */
  } finally {
    reloading.value = false
  }
}

onMounted(() => {
  if (!isLogin.value) loadMeta()
  if (narrowQuery.matches) collapsed.value = true
  narrowQuery.addEventListener('change', onNarrowChange)
})

onBeforeUnmount(() => {
  narrowQuery.removeEventListener('change', onNarrowChange)
})
watch(isLogin, (v) => {
  if (!v) loadMeta()
})
</script>

<template>
  <router-view v-if="isLogin" />
  <el-container v-else class="layout">
    <el-aside
      :width="collapsed ? '64px' : '232px'"
      class="sidebar"
      :class="{ 'is-collapsed': collapsed }"
    >
      <div class="sidebar-fx" aria-hidden="true"></div>
      <div class="brand">
        <div class="brand-mark">
          <GatewayLogo :size="collapsed ? 30 : 34" />
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

      <button
        class="collapse-toggle"
        :title="collapsed ? '展开菜单' : '收起菜单'"
        @click="toggleSidebar"
      >
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起</span>
      </button>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div>
          <div class="header-title">{{ current.title }}</div>
          <div class="header-sub">{{ current.subtitle }}</div>
        </div>
        <div class="header-actions">
          <el-tag type="info" effect="plain" round class="provider-tag">
            供应商：{{ meta.providers.join(' / ') }}
          </el-tag>
          <el-button :loading="reloading" @click="reloadConfig">
            <el-icon><Refresh /></el-icon><span class="hide-sm">&nbsp;刷新配置</span>
          </el-button>
          <el-dropdown @command="logout">
            <span class="user-chip">
              <el-icon><User /></el-icon>&nbsp;{{ username }}
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}

.sidebar {
  position: relative;
  background: var(--app-sidebar-bg);
  display: flex;
  flex-direction: column;
  border-right: none;
  transition: width 0.2s ease;
  overflow: hidden;
}
/* 侧栏动态科技底纹:网格 + 缓慢流动的极光渐变,位于内容之下 */
.sidebar-fx {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image:
    radial-gradient(120% 60% at 15% 0%, rgba(79, 70, 229, 0.45), transparent 60%),
    radial-gradient(90% 50% at 100% 100%, rgba(34, 211, 238, 0.22), transparent 60%),
    linear-gradient(rgba(148, 163, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 255, 0.06) 1px, transparent 1px);
  background-size:
    100% 100%,
    100% 100%,
    100% 28px,
    28px 100%;
  animation: sidebar-aurora 16s ease-in-out infinite alternate;
}
.sidebar > *:not(.sidebar-fx) {
  position: relative;
  z-index: 1;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px 16px;
  color: #fff;
}
.brand-mark {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(79, 70, 229, 0.35), rgba(139, 92, 246, 0.28));
  border: 1px solid rgba(165, 180, 252, 0.35);
  box-shadow:
    0 0 0 1px rgba(129, 140, 248, 0.15),
    0 6px 20px rgba(79, 70, 229, 0.35);
}
.brand-name {
  font-weight: 700;
  font-size: 15px;
  line-height: 1.1;
  background: linear-gradient(90deg, #ffffff, #c7d2fe 55%, #a5f3fc);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.brand-sub {
  font-size: 12px;
  color: var(--app-sidebar-text);
  margin-top: 2px;
}

.menu {
  flex: 1;
  background: transparent;
  border-right: none;
  padding: 6px 12px;
}
.menu :deep(.el-menu-item) {
  color: var(--app-sidebar-text);
  border-radius: 8px;
  margin: 4px 0;
  height: 44px;
  transition:
    background 0.2s ease,
    color 0.2s ease,
    box-shadow 0.2s ease;
}
.menu :deep(.el-menu-item:hover) {
  background: var(--app-sidebar-bg-soft);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, var(--el-color-primary), #6d28d9);
  color: #fff;
  box-shadow: 0 6px 18px rgba(79, 70, 229, 0.4);
}
/* 激活项左侧发光指示条 */
.menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 22px;
  border-radius: 0 3px 3px 0;
  background: #a5f3fc;
  box-shadow: 0 0 10px rgba(165, 243, 252, 0.9);
}
.menu :deep(.el-menu-item.is-active .el-icon) {
  color: #fff;
}

.sidebar-foot {
  padding: 14px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.foot-label {
  font-size: 11px;
  color: #7f86a3;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.foot-value {
  font-size: 13px;
  color: #e6e8f2;
  margin-top: 4px;
  word-break: break-all;
}

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

.header {
  position: relative;
  height: 68px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
/* 顶部动态渐变发光线 */
.header::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: linear-gradient(
    90deg,
    transparent,
    var(--el-color-primary),
    #22d3ee,
    #8b5cf6,
    transparent
  );
  background-size: 200% 100%;
  animation: header-sheen 8s linear infinite;
  opacity: 0.8;
}
.header-title {
  font-size: 17px;
  font-weight: 700;
}
.header-sub {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-top: 2px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-chip {
  display: inline-flex;
  align-items: center;
  cursor: pointer;
  color: var(--app-text-secondary);
  font-size: 14px;
}

.main {
  background: var(--app-bg);
  padding: 0;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@keyframes sidebar-aurora {
  0% {
    background-position:
      0% 0%,
      100% 100%,
      0 0,
      0 0;
    filter: hue-rotate(0deg);
  }
  100% {
    background-position:
      20% 10%,
      80% 90%,
      0 0,
      0 0;
    filter: hue-rotate(-18deg);
  }
}
@keyframes header-sheen {
  0% {
    background-position: 150% 0;
  }
  100% {
    background-position: -50% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-fx {
    animation: none;
  }
  .header::after {
    animation: none;
  }
}

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
</style>
