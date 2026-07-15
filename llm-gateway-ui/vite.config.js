import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 开发服务器把 /admin 与 /v1 代理到后端网关，规避跨域（后端也已开启 /admin/** CORS 作兜底）
export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 按需引入:模板中的 el-* 组件连同样式按使用自动注入;
    // JS API(ElMessage 等)与 v-loading 指令保持显式引入,样式在 main.js 手动补(resolver 只覆盖模板)
    Components({ resolvers: [ElementPlusResolver()], dts: false }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/admin': { target: 'http://localhost:8080', changeOrigin: true },
      '/v1': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
