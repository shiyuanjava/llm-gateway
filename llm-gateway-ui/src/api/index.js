import http from './http'

/** 管理端登录 */
export const authApi = {
  login: (data) => http.post('/admin/auth/login', data),
  me: () => http.get('/admin/auth/me'),
}

/** 元信息 + 配置刷新 */
export const metaApi = {
  get: () => http.get('/admin/meta'),
  reload: () => http.post('/admin/meta/reload'),
}

/** API Key 管理 */
export const apiKeyApi = {
  list: () => http.get('/admin/api-keys'),
  create: (data) => http.post('/admin/api-keys', data),
  update: (id, data) => http.put(`/admin/api-keys/${id}`, data),
  remove: (id) => http.delete(`/admin/api-keys/${id}`),
}

/** 路由规则管理 */
export const routingApi = {
  list: () => http.get('/admin/routing-rules'),
  create: (data) => http.post('/admin/routing-rules', data),
  update: (id, data) => http.put(`/admin/routing-rules/${id}`, data),
  remove: (id) => http.delete(`/admin/routing-rules/${id}`),
}

/** 计费单价管理 */
export const pricingApi = {
  list: () => http.get('/admin/pricing'),
  create: (data) => http.post('/admin/pricing', data),
  update: (id, data) => http.put(`/admin/pricing/${id}`, data),
  remove: (id) => http.delete(`/admin/pricing/${id}`),
}

/** 请求日志查询 */
export const logApi = {
  list: (params) => http.get('/admin/logs', { params }),
  stats: () => http.get('/admin/logs/stats'),
}

/** 管理面审计日志 */
export const auditApi = {
  list: (params) => http.get('/admin/audit-logs', { params }),
}
