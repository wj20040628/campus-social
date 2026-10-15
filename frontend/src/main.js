import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useUserStore } from './stores/user'

/* ---------------------------------------------------------------------------
 * 引入顺序即 CSS 优先级,不可调换(SPEC §11 坑 5 / ADR-009)
 *
 * 1. element-plus 样式:由 unplugin-vue-components + ElementPlusResolver
 *    按需注入(组件被模板引用时才import 其 css)。EP 的 base.css 在 :root
 *    里定义了 --el-color-primary: #409eff 等默认值。
 * 2. design-tokens.css:必须在其后,把 --ys-* 映射覆盖到 --el-*(Bridge 段)。
 *    CSS 变量后定义者胜,顺序反了元素颜色会「不生效」且不报错。
 * 3. tokens.css:兼容垫片,只做旧变量名映射,不再重定义 --el-*。
 *
 * TD-21 修复:这里曾有 `app.use(ElementPlus)` + `import 'element-plus/dist/index.css'`
 * 的整包注册,与按需引入互相打架,导致 element-plus 被打成 943KB(实测,gzip 303KB),
 * 单独一个 chunk 就超了 AC-17 的 300KB gzip 上限。两者均已删除。
 * --------------------------------------------------------------------------- */
import './styles/design-tokens.css'
import './styles/tokens.css'
/* EP 深色变量:必须在本文件引入,且放在 design-tokens.css 之后。
 * 它把 --el-* 在 html.dark 下换成深色值,供 el-dialog / el-button /
 * el-message 等原生组件在深色主题下取色。顺序反了会被浅色值盖回。 */
import 'element-plus/theme-chalk/dark/css-vars.css'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

// 主题必须在挂载前落到 <html>,否则先浅后深会闪一下白
import { initTheme } from './composables/useTheme'
initTheme()

app.mount('#app')

// 全局拉一次当前用户信息:无论刷新、新开标签还是直接进首页,
// 都保证 userStore.userInfo 有值(否则顶栏头像/个人主页/主页按钮全部失效)。
// 只在有 token(已登录)时拉,失败静默(拦截器已处理过期跳登录)。
const userStore = useUserStore()
if (userStore.hasToken) {
  userStore.fetchMe().catch(() => {})
}