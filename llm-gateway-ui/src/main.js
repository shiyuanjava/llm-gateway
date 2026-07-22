import { createApp } from 'vue'
// 组件与其样式由 unplugin-vue-components 按模板使用自动注入(见 vite.config.js);
// 这里只补插件覆盖不到的部分:v-loading 指令注册,以及 JS API(ElMessage/ElMessageBox)的样式
import { vLoading } from 'element-plus'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
// 只全局注册路由 meta 里按名字引用的菜单图标;各视图自己 import 所需图标
import { DataLine, Key, Share, Money, Tickets, Stamp, ChatDotRound } from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)

app.directive('loading', vLoading)
for (const icon of [DataLine, Key, Share, Money, Tickets, Stamp, ChatDotRound]) {
  app.component(icon.name, icon)
}

app.use(router)
app.mount('#app')
