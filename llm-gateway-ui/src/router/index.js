import { createRouter, createWebHistory } from 'vue-router'
import { getToken, clearSession, isSessionExpired } from '../auth/session'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue')
  },
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '概览', subtitle: '租户用量与上游成本统计', icon: 'DataLine' }
  },
  {
    path: '/api-keys',
    name: 'api-keys',
    component: () => import('../views/ApiKeys.vue'),
    meta: { title: 'API Key', subtitle: '密钥与租户、角色、可用模型', icon: 'Key' }
  },
  {
    path: '/routing',
    name: 'routing',
    component: () => import('../views/RoutingRules.vue'),
    meta: { title: '路由规则', subtitle: '别名 → 首选 / 升级 / 降级链', icon: 'Share' }
  },
  {
    path: '/pricing',
    name: 'pricing',
    component: () => import('../views/Pricing.vue'),
    meta: { title: '计费单价', subtitle: '各模型每 1K Token 单价', icon: 'Money' }
  },
  {
    path: '/logs',
    name: 'logs',
    component: () => import('../views/Logs.vue'),
    meta: { title: '请求日志', subtitle: '审计、用量与成本记录', icon: 'Tickets' }
  },
  {
    path: '/audit',
    name: 'audit',
    component: () => import('../views/AuditLogs.vue'),
    meta: { title: '操作审计', subtitle: '管理面登录与写操作记录', icon: 'Stamp' }
  },
  {
    path: '/playground',
    name: 'playground',
    component: () => import('../views/Playground.vue'),
    meta: { title: '试运行', subtitle: '直连 /v1 验证网关与流式输出', icon: 'ChatDotRound' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局守卫:已知过期的会话先清理再去登录页(避免发出必 401 的请求);未登录一律去登录页
router.beforeEach((to) => {
  if (getToken() && isSessionExpired()) {
    clearSession()
    if (to.path !== '/login') {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }
  if (to.path !== '/login' && !getToken()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && getToken()) {
    return { path: '/dashboard' }
  }
  return true
})

export default router
