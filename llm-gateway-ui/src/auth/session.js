/**
 * 管理端登录态:token、用户名与过期时刻存 localStorage。
 * 过期时刻(epoch 毫秒)来自登录响应 expiresAt;旧会话无该值视为有效,由 401 拦截器兜底。
 */
const TOKEN_KEY = 'gw_admin_token'
const USER_KEY = 'gw_admin_user'
const EXPIRES_KEY = 'gw_admin_expires_at'

export function getToken() { return localStorage.getItem(TOKEN_KEY) || '' }
export function getUsername() { return localStorage.getItem(USER_KEY) || '' }
export function getExpiresAt() {
  const v = localStorage.getItem(EXPIRES_KEY)
  return v ? Number(v) : 0
}

export function setSession(token, username, expiresAt) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, username)
  if (expiresAt) localStorage.setItem(EXPIRES_KEY, String(expiresAt))
  else localStorage.removeItem(EXPIRES_KEY)
}

/** 已知过期时刻且已到期(旧会话无 expiresAt 返回 false,交给 401 兜底) */
export function isSessionExpired() {
  const at = getExpiresAt()
  return at > 0 && Date.now() >= at
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(EXPIRES_KEY)
}
