/**
 * 管理端登录态:token、用户名与过期时刻存 sessionStorage。
 * 用 sessionStorage 而非 localStorage:XSS 窃取窗口缩短到标签页生命周期,关标签即清;
 * 代价是新标签页需重新登录(管理台可接受)。根治需后端下发 HttpOnly Cookie(同源反代天然适配)。
 * 过期时刻(epoch 毫秒)来自登录响应 expiresAt;旧会话无该值视为有效,由 401 拦截器兜底。
 */
import { ref } from 'vue'

const TOKEN_KEY = 'gw_admin_token'
const USER_KEY = 'gw_admin_user'
const EXPIRES_KEY = 'gw_admin_expires_at'

/** 响应式当前用户名:登录/登出即时更新,替代靠路由变化「骗」重算的写法 */
export const currentUsername = ref(sessionStorage.getItem(USER_KEY) || '')

export function getToken() {
  return sessionStorage.getItem(TOKEN_KEY) || ''
}
export function getUsername() {
  return sessionStorage.getItem(USER_KEY) || ''
}
export function getExpiresAt() {
  const v = sessionStorage.getItem(EXPIRES_KEY)
  return v ? Number(v) : 0
}

export function setSession(token, username, expiresAt) {
  sessionStorage.setItem(TOKEN_KEY, token)
  sessionStorage.setItem(USER_KEY, username)
  if (expiresAt) sessionStorage.setItem(EXPIRES_KEY, String(expiresAt))
  else sessionStorage.removeItem(EXPIRES_KEY)
  currentUsername.value = username
}

/** 已知过期时刻且已到期(旧会话无 expiresAt 返回 false,交给 401 兜底) */
export function isSessionExpired() {
  const at = getExpiresAt()
  return at > 0 && Date.now() >= at
}

export function clearSession() {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(USER_KEY)
  sessionStorage.removeItem(EXPIRES_KEY)
  currentUsername.value = ''
  // 迁移清理:清掉历史版本遗留在 localStorage 的会话,避免旧 token 残留
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(EXPIRES_KEY)
}
