import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

const positiveInteger = (value, fallback) => {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

// 开发服务器把 /admin 与 /v1 代理到同一个后端目标，保证管理面与 Playground 链路一致。
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = (env.VITE_PROXY_TARGET || 'http://localhost:8080').replace(/\/+$/, '')
  const proxyTimeout = positiveInteger(env.VITE_PROXY_TIMEOUT_MS, 330_000)
  const proxyOptions = {
    target: proxyTarget,
    changeOrigin: true,
    xfwd: true,
    secure: env.VITE_PROXY_SECURE !== 'false',
    timeout: proxyTimeout,
    proxyTimeout,
  }

  return {
    plugins: [
      vue(),
      // Element Plus 按需引入:模板中的 el-* 组件连同样式按使用自动注入;
      // JS API(ElMessage 等)与 v-loading 指令保持显式引入,样式在 main.js 手动补(resolver 只覆盖模板)
      Components({ resolvers: [ElementPlusResolver()], dts: false }),
    ],
    server: {
      host: env.VITE_DEV_HOST || 'localhost',
      port: positiveInteger(env.VITE_DEV_PORT, 5173),
      // 端口漂移会让固定 CORS 白名单失效，直接失败比静默切到 5174 更容易定位。
      strictPort: true,
      proxy: {
        '/admin': { ...proxyOptions },
        '/v1': { ...proxyOptions },
      },
    },
  }
})
