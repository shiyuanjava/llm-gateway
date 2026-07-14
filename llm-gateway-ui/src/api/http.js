import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { getToken, clearSession } from '../auth/session'

/**
 * 统一的 axios 实例。
 * 后端返回 { code, msg, data }，这里在拦截器里拆包：成功直接返回 data，失败弹错误并 reject。
 * 开发期通过 Vite proxy 把 /admin 转发到后端 8080。
 */
const http = axios.create({
  // 生产部署时通过 VITE_API_BASE 指定后端地址；开发期留空走 Vite proxy
  baseURL: import.meta.env.VITE_API_BASE || '',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
    ElMessage.error(body.msg || '请求失败')
    return Promise.reject(new Error(body.msg || '请求失败'))
  },
  (error) => {
    const onLoginPage = router.currentRoute.value.path === '/login'
    if (error.response?.status === 401) {
      clearSession()
      if (onLoginPage) {
        return Promise.reject(error) // 登录页自行提示
      }
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      ElMessage.error('未登录或登录已过期')
      return Promise.reject(error)
    }
    if (onLoginPage) {
      return Promise.reject(error) // 登录页的错误(如 423 锁定)由 Login.vue 统一提示,避免双 toast
    }
    const msg = error.response?.data?.msg || error.message || '网络错误'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default http
