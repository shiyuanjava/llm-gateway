const DEFAULT_API_TIMEOUT_MS = 30_000
const DEFAULT_STREAM_TIMEOUT_MS = 310_000

function normalizeBaseUrl(value) {
  const raw = String(value || '').trim()
  if (!raw || raw === '/') return ''

  const normalized = raw.startsWith('/') || /^[a-z][a-z\d+.-]*:\/\//i.test(raw) ? raw : `/${raw}`
  return normalized.replace(/\/+$/, '')
}

function positiveInteger(value, fallback) {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

/**
 * 浏览器到网关的统一运行配置。
 * VITE_API_BASE 为空时走同源(Vite/nginx 代理);分域部署时可填 https://api.example.com。
 */
export const API_BASE_URL = normalizeBaseUrl(import.meta.env.VITE_API_BASE)
export const API_TIMEOUT_MS = positiveInteger(
  import.meta.env.VITE_API_TIMEOUT_MS,
  DEFAULT_API_TIMEOUT_MS
)
export const STREAM_TIMEOUT_MS = positiveInteger(
  import.meta.env.VITE_STREAM_TIMEOUT_MS,
  DEFAULT_STREAM_TIMEOUT_MS
)

export const CHAT_COMPLETIONS_PATH = '/v1/chat/completions'

/** 让 axios 与原生 fetch 使用完全一致的 API 基址拼接规则。 */
export function apiUrl(path) {
  const normalizedPath = String(path || '').startsWith('/') ? String(path) : `/${path || ''}`
  return `${API_BASE_URL}${normalizedPath}`
}

/**
 * 生成符合后端 TraceIdFilter 白名单([A-Za-z0-9_-]{1,64})的请求 ID。
 * UI 主动生成后，浏览器、nginx、应用日志与 request_log 可使用同一个 ID 排障。
 */
export function createRequestId() {
  const random = globalThis.crypto?.randomUUID?.().replaceAll('-', '')
  const fallback = `${Date.now().toString(36)}${Math.random().toString(36).slice(2)}`
  return `ui_${random || fallback}`.slice(0, 64)
}
