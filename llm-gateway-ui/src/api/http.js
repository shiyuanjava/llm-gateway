import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { getToken, clearSession } from '../auth/session'
import { API_BASE_URL, API_TIMEOUT_MS, createRequestId } from '../config/runtime'
import { extractGatewayMessage } from './error'

const REQUEST_ID_HEADER = 'X-Request-Id'
let handlingUnauthorized = false

/**
 * 统一的 axios 实例。
 * 后端返回 { code, msg, data }，这里在拦截器里拆包：成功直接返回 data，失败弹错误并 reject。
 * 开发期通过 Vite proxy 把 /admin 转发到后端 8080。
 */
const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: API_TIMEOUT_MS,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  if (!config.headers.get(REQUEST_ID_HEADER)) {
    config.headers.set(REQUEST_ID_HEADER, createRequestId())
  }
  return config
})

router.afterEach((to) => {
  // 登录成功离开登录页后，允许下一次真实会话过期再次提示。
  if (to.path !== '/login') handlingUnauthorized = false
})

function requestIdOf(response, config) {
  return response?.headers?.['x-request-id'] || config?.headers?.get?.(REQUEST_ID_HEADER) || ''
}

function withRequestId(message, requestId) {
  return requestId ? `${message} · 请求 ID ${requestId}` : message
}

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 非包装结构（少数情况）直接返回
    if (body == null || typeof body.code === 'undefined') {
      return body
    }
    if (body.code === 0) {
      return body.data
    }
    const requestId = requestIdOf(response, response.config)
    const message = extractGatewayMessage(body, '请求失败')
    const error = new Error(message)
    error.code = body.code
    error.requestId = requestId
    error.response = response
    if (router.currentRoute.value.path !== '/login') {
      ElMessage.error(withRequestId(message, requestId))
    }
    return Promise.reject(error)
  },
  (error) => {
    const requestId = requestIdOf(error.response, error.config)
    error.requestId = requestId
    const onLoginPage = router.currentRoute.value.path === '/login'
    if (error.response?.status === 401) {
      clearSession()
      if (onLoginPage) {
        return Promise.reject(error) // 登录页自行提示
      }
      if (!handlingUnauthorized) {
        handlingUnauthorized = true
        const redirect = router.currentRoute.value.fullPath
        ElMessage.error(withRequestId('未登录或登录已过期', requestId))
        void router.replace({ path: '/login', query: { redirect } })
      }
      return Promise.reject(error)
    }
    if (onLoginPage) {
      return Promise.reject(error) // 登录页的错误(如 423 锁定)由 Login.vue 统一提示,避免双 toast
    }
    const msg = extractGatewayMessage(error)
    ElMessage.error(withRequestId(msg, requestId))
    return Promise.reject(error)
  }
)

export default http
