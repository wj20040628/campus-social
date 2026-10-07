import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/tokens.css'
import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.mount('#app')

// 全局拉一次当前用户信息:无论刷新、新开标签还是直接进首页,
// 都保证 userStore.userInfo 有值(否则顶栏头像/个人主页/主页按钮全部失效)。
// 只在有 token(已登录)时拉,失败静默(拦截器已处理过期跳登录)。
const userStore = useUserStore()
if (userStore.hasToken) {
  userStore.fetchMe().catch(() => {})
}
