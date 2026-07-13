<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, User } from '@element-plus/icons-vue'
import { metaApi } from './api'
import { getUsername, clearSession } from './auth/session'

const route = useRoute()
const router = useRouter()

const isLogin = computed(() => route.path === '/login')
// 依赖 route.path:登录/退出都会切路由,借此让用户名重新读取 localStorage
const username = computed(() => (route.path, getUsername()))

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
  subtitle: route.meta?.subtitle || ''
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
  } finally {
    reloading.value = false
  }
}

onMounted(() => { if (!isLogin.value) loadMeta() })
watch(isLogin, (v) => { if (!v) loadMeta() })
</script>

<template>
  <router-view v-if="isLogin" />
  <el-container v-else class="layout">
    <el-aside width="232px" class="sidebar">
      <div class="brand">
        <div class="brand-mark"><el-icon :size="20"><Cpu /></el-icon></div>
        <div class="brand-text">
          <div class="brand-name">LLM Gateway</div>
          <div class="brand-sub">控制台</div>
        </div>
      </div>

      <el-menu :default-active="route.path" router class="menu" :collapse="false">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>

      <div class="sidebar-foot">
        <div class="foot-label">默认模型</div>
        <div class="foot-value tabular-nums">
          {{ meta.defaultProvider }} / {{ meta.defaultModel }}
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div>
          <div class="header-title">{{ current.title }}</div>
          <div class="header-sub">{{ current.subtitle }}</div>
        </div>
        <div class="header-actions">
          <el-tag type="info" effect="plain" round>
            供应商：{{ meta.providers.join(' / ') }}
          </el-tag>
          <el-button :loading="reloading" @click="reloadConfig">
            <el-icon><Refresh /></el-icon>&nbsp;刷新配置
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
.layout { height: 100vh; }

.sidebar {
  background: var(--app-sidebar-bg);
  display: flex;
  flex-direction: column;
  border-right: none;
}
.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px 16px;
  color: #fff;
}
.brand-mark {
  width: 38px; height: 38px;
  border-radius: 10px;
  display: grid; place-items: center;
  background: linear-gradient(135deg, var(--el-color-primary), #8b5cf6);
  color: #fff;
}
.brand-name { font-weight: 700; font-size: 15px; line-height: 1.1; }
.brand-sub { font-size: 12px; color: var(--app-sidebar-text); margin-top: 2px; }

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
}
.menu :deep(.el-menu-item:hover) {
  background: var(--app-sidebar-bg-soft);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary);
  color: #fff;
}
.menu :deep(.el-menu-item.is-active .el-icon) { color: #fff; }

.sidebar-foot {
  padding: 14px 20px;
  border-top: 1px solid rgba(255, 255, 255, .08);
}
.foot-label { font-size: 11px; color: #7f86a3; text-transform: uppercase; letter-spacing: .04em; }
.foot-value { font-size: 13px; color: #e6e8f2; margin-top: 4px; word-break: break-all; }

.header {
  height: 68px;
  background: var(--app-surface);
  border-bottom: 1px solid var(--app-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}
.header-title { font-size: 17px; font-weight: 700; }
.header-sub { font-size: 12px; color: var(--app-text-secondary); margin-top: 2px; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.user-chip { display: inline-flex; align-items: center; cursor: pointer; color: var(--app-text-secondary); font-size: 14px; }

.main {
  background: var(--app-bg);
  padding: 0;
  overflow-y: auto;
}

.fade-enter-active, .fade-leave-active { transition: opacity .18s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
