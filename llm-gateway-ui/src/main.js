import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 只全局注册 App.vue 品牌图标与路由 meta 里按名字引用的菜单图标;各视图自己 import 所需图标
import {
  Cpu,
  DataLine,
  Key,
  Share,
  Money,
  Tickets,
  Stamp,
  ChatDotRound,
} from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

for (const icon of [Cpu, DataLine, Key, Share, Money, Tickets, Stamp, ChatDotRound]) {
  app.component(icon.name, icon)
}

app.use(ElementPlus)
app.use(router)
app.mount('#app')
