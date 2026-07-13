import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器把 /admin 与 /v1 代理到后端网关，规避跨域（后端也已开启 /admin/** CORS 作兜底）
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/admin': { target: 'http://localhost:8080', changeOrigin: true },
      '/v1': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
