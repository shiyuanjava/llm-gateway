<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  RefreshCw,
  UserRound,
  PanelLeftClose,
  PanelLeftOpen,
  LogOut,
  RadioTower,
  Clock3,
  Activity,
} from 'lucide-vue-next'
import { metaApi } from './api'
import { clearSession, currentUsername } from './auth/session'
import GatewayLogo from './components/GatewayLogo.vue'

const route = useRoute()
const router = useRouter()
const clock = ref('')
let clockTimer = 0

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
    .map((r, index) => ({
      path: r.path,
      title: r.meta.title,
      icon: r.meta.icon,
      index: String(index + 1).padStart(2, '0'),
    }))
)

const current = computed(() => ({
  title: route.meta?.title || '',
  subtitle: route.meta?.subtitle || '',
  code: route.meta?.code || '',
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

function updateClock() {
  clock.value = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date())
}

function trackPointer(event) {
  const el = event.currentTarget
  const rect = el.getBoundingClientRect()
  el.style.setProperty('--pointer-x', `${event.clientX - rect.left}px`)
  el.style.setProperty('--pointer-y', `${event.clientY - rect.top}px`)
}

onMounted(() => {
  if (!isLogin.value) loadMeta()
  if (narrowQuery.matches) collapsed.value = true
  narrowQuery.addEventListener('change', onNarrowChange)
  updateClock()
  clockTimer = window.setInterval(updateClock, 30_000)
})

onBeforeUnmount(() => {
  narrowQuery.removeEventListener('change', onNarrowChange)
  window.clearInterval(clockTimer)
})
watch(isLogin, (v) => {
  if (!v) loadMeta()
})
</script>

<template>
  <router-view v-if="isLogin" />
  <el-container v-else class="layout" @pointermove="trackPointer">
    <div class="shell-spotlight" aria-hidden="true"></div>

    <el-aside
      :width="collapsed ? '72px' : '252px'"
      class="sidebar"
      :class="{ 'is-collapsed': collapsed }"
    >
      <div class="sidebar-fx" aria-hidden="true"></div>
      <div class="brand">
        <div class="brand-mark"><GatewayLogo :size="collapsed ? 31 : 35" /></div>
        <div v-if="!collapsed" class="brand-text">
          <div class="brand-name">LLM GATEWAY</div>
          <div class="brand-sub mono">SIGNAL CONTROL / 01</div>
        </div>
      </div>

      <div v-if="!collapsed" class="nav-caption mono">NAVIGATION / INDEX</div>
      <el-menu
        :default-active="route.path"
        router
        class="menu"
        :collapse="collapsed"
        :collapse-transition="false"
      >
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <span v-if="!collapsed" class="menu-index mono">{{ m.index }}</span>
          <el-icon><component :is="m.icon" :stroke-width="1.65" /></el-icon>
          <span>{{ m.title }}</span>
          <span v-if="!collapsed" class="menu-tick" aria-hidden="true"></span>
        </el-menu-item>
      </el-menu>

      <div v-if="!collapsed" class="sidebar-foot">
        <div class="engine-state mono">
          <Activity :size="13" :stroke-width="1.8" />
          <span>ROUTING ENGINE</span>
          <i aria-hidden="true"></i>
        </div>
        <div class="foot-label mono">DEFAULT TARGET</div>
        <div class="foot-value tabular-nums">
          {{ meta.defaultProvider || 'unassigned' }} / {{ meta.defaultModel || 'default' }}
        </div>
      </div>

      <button
        class="collapse-toggle"
        :title="collapsed ? '展开菜单' : '收起菜单'"
        :aria-label="collapsed ? '展开菜单' : '收起菜单'"
        @click="toggleSidebar"
      >
        <PanelLeftOpen v-if="collapsed" :size="17" :stroke-width="1.7" />
        <PanelLeftClose v-else :size="17" :stroke-width="1.7" />
        <span v-if="!collapsed" class="mono">COLLAPSE</span>
      </button>
    </el-aside>

    <el-container class="content-shell">
      <el-header class="header">
        <div class="header-ghost mono" aria-hidden="true">{{ current.code }}</div>
        <div class="header-left">
          <div class="header-kicker mono"><span></span>{{ current.code }} / LIVE</div>
          <div class="header-title">{{ current.title }}</div>
          <div class="header-sub">{{ current.subtitle }}</div>
        </div>

        <div class="header-actions">
          <div class="clock-block mono">
            <Clock3 :size="14" :stroke-width="1.7" />
            <span>{{ clock }}</span>
            <small>UTC+08</small>
          </div>
          <div v-if="meta.providers.length" class="provider-pill mono">
            <RadioTower :size="14" :stroke-width="1.7" />
            <span>{{ meta.providers.length }} PROVIDERS</span>
          </div>
          <button
            class="icon-action"
            :class="{ 'is-spinning': reloading }"
            :disabled="reloading"
            title="刷新配置"
            aria-label="刷新配置"
            @click="reloadConfig"
          >
            <RefreshCw :size="17" :stroke-width="1.7" />
          </button>
          <el-dropdown @command="logout">
            <button class="user-chip" aria-label="用户菜单">
              <span class="user-avatar"><UserRound :size="15" :stroke-width="1.8" /></span>
              <span class="user-name">{{ username }}</span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><LogOut :stroke-width="1.8" /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <div class="main-grid" aria-hidden="true"></div>
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  --pointer-x: 70vw;
  --pointer-y: 30vh;
  position: relative;
  height: 100vh;
  isolation: isolate;
  overflow: hidden;
}

.shell-spotlight {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background: radial-gradient(
    520px circle at var(--pointer-x) var(--pointer-y),
    rgba(124, 92, 255, 0.09),
    transparent 68%
  );
}

.sidebar {
  position: relative;
  z-index: 8;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: rgba(7, 8, 9, 0.95);
  border-right: 1px solid rgba(255, 255, 255, 0.09);
  transition: width 0.38s var(--out-expo);
}

.sidebar-fx {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    linear-gradient(145deg, rgba(124, 92, 255, 0.13), transparent 31%),
    radial-gradient(90% 35% at 20% 100%, rgba(200, 255, 61, 0.06), transparent 65%),
    repeating-linear-gradient(0deg, transparent 0 31px, rgba(255, 255, 255, 0.025) 31px 32px);
}

.sidebar-fx::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 1px;
  height: 28%;
  background: linear-gradient(var(--accent-lime), transparent);
  box-shadow: 0 0 14px rgba(200, 255, 61, 0.4);
  animation: sidebar-scan 6s var(--swift) infinite;
}

.sidebar > *:not(.sidebar-fx) {
  position: relative;
  z-index: 1;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 84px;
  padding: 18px 17px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.brand-mark {
  width: 46px;
  height: 46px;
  flex: none;
  display: grid;
  place-items: center;
  background: rgba(124, 92, 255, 0.1);
  border: 1px solid rgba(159, 142, 255, 0.28);
  box-shadow: inset 0 0 22px rgba(124, 92, 255, 0.1), 0 0 30px rgba(124, 92, 255, 0.12);
  clip-path: polygon(0 0, calc(100% - 10px) 0, 100% 10px, 100% 100%, 10px 100%, 0 calc(100% - 10px));
}

.brand-name {
  white-space: nowrap;
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.075em;
  color: var(--app-text);
}

.brand-sub,
.nav-caption {
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.16em;
}

.brand-sub {
  margin-top: 4px;
}

.nav-caption {
  padding: 22px 20px 8px;
}

.menu {
  flex: 1;
  width: 100%;
  padding: 5px 10px;
  overflow: hidden auto;
  background: transparent;
  border-right: 0;
}

.menu :deep(.el-menu-item) {
  position: relative;
  display: grid;
  align-items: center;
  grid-template-columns: 24px 22px 1fr 8px;
  gap: 9px;
  height: 48px;
  margin: 3px 0;
  padding: 0 12px !important;
  color: var(--app-sidebar-text);
  border: 1px solid transparent;
  border-radius: 0;
  transition: color 0.22s var(--swift), background 0.22s var(--swift), border-color 0.22s var(--swift), transform 0.22s var(--out-expo);
}

.menu :deep(.el-menu-item:hover) {
  color: var(--app-text);
  background: rgba(255, 255, 255, 0.035);
  border-color: rgba(255, 255, 255, 0.06);
  transform: translateX(3px);
}

.menu :deep(.el-menu-item.is-active) {
  color: #090b09;
  background: var(--accent-lime);
  border-color: var(--accent-lime);
  box-shadow: 0 12px 32px rgba(200, 255, 61, 0.1);
}

.menu :deep(.el-menu-item .el-icon) {
  margin: 0;
}

.menu :deep(.el-menu-item > span:not(.menu-index):not(.menu-tick)) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-index {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--app-text-faint);
}

.menu :deep(.is-active) .menu-index {
  color: rgba(9, 11, 9, 0.5);
}

.menu-tick {
  width: 5px;
  height: 5px;
  border: 1px solid currentColor;
  transform: rotate(45deg) scale(0);
  transition: transform 0.25s var(--spring);
}

.menu :deep(.is-active) .menu-tick {
  transform: rotate(45deg) scale(1);
}

.sidebar-foot {
  padding: 16px 18px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}

.engine-state {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 18px;
  color: var(--app-text-secondary);
  font-size: 9px;
  letter-spacing: 0.12em;
}

.engine-state i {
  width: 6px;
  height: 6px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--accent-lime);
  box-shadow: 0 0 12px rgba(200, 255, 61, 0.8);
  animation: status-pulse 2.2s ease-in-out infinite;
}

.foot-label {
  color: var(--app-text-faint);
  font-size: 9px;
  letter-spacing: 0.16em;
}

.foot-value {
  margin-top: 5px;
  overflow: hidden;
  color: var(--app-text);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.collapse-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  width: 100%;
  height: 48px;
  flex: none;
  color: var(--app-text-faint);
  background: transparent;
  border: 0;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
  cursor: pointer;
  transition: color 0.2s var(--swift), background 0.2s var(--swift);
}

.collapse-toggle span {
  font-size: 9px;
  letter-spacing: 0.15em;
}

.collapse-toggle:hover {
  color: var(--accent-lime);
  background: rgba(200, 255, 61, 0.035);
}

.is-collapsed .brand {
  justify-content: center;
  padding-inline: 0;
}

.is-collapsed .menu {
  padding-inline: 8px;
}

.is-collapsed .menu :deep(.el-menu-item) {
  display: flex;
  justify-content: center;
  padding: 0 !important;
}

.content-shell {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.header {
  position: relative;
  z-index: 6;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 84px;
  padding: 0 30px;
  overflow: hidden;
  background: rgba(8, 9, 12, 0.76);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(20px) saturate(125%);
}

.header::after {
  content: '';
  position: absolute;
  right: 0;
  bottom: -1px;
  width: 34%;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--accent-lime));
  animation: header-sheen 5s var(--swift) infinite alternate;
}

.header-ghost {
  position: absolute;
  right: 31%;
  bottom: -25px;
  color: transparent;
  font-size: 69px;
  font-weight: 600;
  letter-spacing: -0.05em;
  -webkit-text-stroke: 1px rgba(255, 255, 255, 0.045);
  pointer-events: none;
  user-select: none;
}

.header-left {
  position: relative;
  min-width: 0;
}

.header-kicker {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 3px;
  color: var(--accent-lime);
  font-size: 8px;
  letter-spacing: 0.18em;
}

.header-kicker span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 10px currentColor;
}

.header-title {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.03em;
}

.header-sub {
  margin-top: 1px;
  overflow: hidden;
  color: var(--app-text-faint);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-actions {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
}

.clock-block,
.provider-pill {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 38px;
  padding: 0 12px;
  color: var(--app-text-secondary);
  font-size: 10px;
  letter-spacing: 0.08em;
  border-left: 1px solid rgba(255, 255, 255, 0.09);
}

.clock-block small {
  color: var(--app-text-faint);
  font-size: 8px;
}

.provider-pill {
  color: var(--accent-cyan);
  background: rgba(83, 217, 255, 0.045);
  border: 1px solid rgba(83, 217, 255, 0.14);
}

.icon-action,
.user-chip {
  height: 38px;
  color: var(--app-text-secondary);
  background: rgba(255, 255, 255, 0.025);
  border: 1px solid rgba(255, 255, 255, 0.09);
  cursor: pointer;
  transition: color 0.2s var(--swift), border-color 0.2s var(--swift), background 0.2s var(--swift), transform 0.2s var(--spring);
}

.icon-action {
  width: 38px;
  display: grid;
  place-items: center;
}

.icon-action:hover,
.user-chip:hover {
  color: var(--accent-lime);
  background: rgba(200, 255, 61, 0.04);
  border-color: rgba(200, 255, 61, 0.28);
  transform: translateY(-1px);
}

.icon-action.is-spinning svg {
  animation: spin 0.8s linear infinite;
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 0 12px 0 5px;
  font-family: inherit;
}

.user-avatar {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  color: #090b09;
  background: var(--accent-lime);
}

.user-name {
  max-width: 110px;
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.main {
  position: relative;
  min-width: 0;
  padding: 0;
  overflow: hidden auto;
  background: transparent;
}

.main-grid {
  position: fixed;
  inset: 84px 0 0 252px;
  z-index: 0;
  pointer-events: none;
  opacity: 0.45;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.022) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.022) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: linear-gradient(180deg, #000, transparent 88%);
}

.main :deep(.page) {
  position: relative;
  z-index: 1;
}

.page-enter-active {
  transition: opacity 0.42s var(--out-expo), transform 0.42s var(--out-expo), filter 0.42s var(--out-expo);
}

.page-leave-active {
  transition: opacity 0.18s var(--swift), transform 0.18s var(--swift);
}

.page-enter-from {
  opacity: 0;
  filter: blur(8px);
  transform: translateY(18px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@keyframes sidebar-scan {
  0%, 100% { transform: translateY(-15%); opacity: 0.35; }
  50% { transform: translateY(310%); opacity: 1; }
}

@keyframes status-pulse {
  50% { opacity: 0.35; transform: scale(0.72); }
}

@keyframes header-sheen {
  from { transform: translateX(25%); opacity: 0.35; }
  to { transform: translateX(-35%); opacity: 1; }
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-fx::after,
  .engine-state i,
  .header::after {
    animation: none;
  }
}

@media (max-width: 992px) {
  .clock-block,
  .provider-pill,
  .header-ghost {
    display: none;
  }

  .main-grid {
    left: 72px;
  }
}

@media (max-width: 700px) {
  .header {
    height: 72px;
    padding: 0 12px;
  }

  .header-sub,
  .header-kicker,
  .user-name {
    display: none;
  }

  .header-actions {
    gap: 6px;
  }

  .user-chip {
    padding-right: 5px;
  }

  .main-grid {
    top: 72px;
  }
}
</style>
