import { createApp } from 'vue'
// 组件与其样式由 unplugin-vue-components 按模板使用自动注入(见 vite.config.js);
// 这里只补插件覆盖不到的部分:v-loading 指令注册,以及 JS API(ElMessage/ElMessageBox)的样式
import { vLoading } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
// 全站深色模式:index.html 的 <html class="dark"> + Element Plus 暗色变量
import 'element-plus/theme-chalk/dark/css-vars.css'

// 自托管字体(不依赖外部 CDN):Space Grotesk 展示字体 + JetBrains Mono 等宽
import '@fontsource/space-grotesk/latin-500.css'
import '@fontsource/space-grotesk/latin-600.css'
import '@fontsource/space-grotesk/latin-700.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import '@fontsource/jetbrains-mono/latin-600.css'

// 图标统一使用 Lucide;只全局注册路由 meta 里按名字引用的菜单图标,各视图自己 import 所需图标
import {
  LayoutDashboard,
  KeyRound,
  Route,
  CircleDollarSign,
  ScrollText,
  ShieldCheck,
  SquareTerminal,
} from 'lucide-vue-next'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

app.directive('loading', vLoading)
const menuIcons = {
  LayoutDashboard,
  KeyRound,
  Route,
  CircleDollarSign,
  ScrollText,
  ShieldCheck,
  SquareTerminal,
}
for (const [name, icon] of Object.entries(menuIcons)) {
  app.component(name, icon)
}

app.use(router)
app.mount('#app')
