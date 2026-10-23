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
/*
 * 2026-10-15:去掉 'element-plus/theme-chalk/dark/css-vars.css' 的引入。
 * 原引入靠 html.dark class 触发,与 useTheme 落的 data-theme="dark" 双轨并行,
 * 特异性冲突导致 Bridge 段(把 --el-* 映射到 --ys-* 的暖色)在深色下被 EP
 * 默认冷灰蓝盖回。现在 useTheme 只落 data-theme="dark" 单一事实,
 * design-tokens.css 的 :root[data-theme="dark"] 段(含 EP Bridge 深色覆写)
 * 全权接管 --el-* 取值,EP 原生组件也吃到友声暖色,无需 EP dark css-vars。
 */

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)

/*
 * ElMessageBox 依赖的全局组件补丁(必须在 app.mount 之前)。
 * 详细原因见 utils/patchMessageBox.js 的注释:MessageBox 内部用
 * resolveComponent 按名字查全局组件,而本项目为压体积没做整包注册,
 * 结果确认框整个渲染不出来 —— 表现为「点退出登录没反应」。
 */
import { patchMessageBox } from './utils/patchMessageBox'
patchMessageBox(app)

// 主题必须在挂载前落到 <html>,否则先浅后深会闪一下白
import { initTheme } from './composables/useTheme'
initTheme()

// 字号档位同理:挂载前落 data-font-scale,避免首屏字号跳一下
import { initFontScale } from './composables/useFontScale'
initFontScale()

app.mount('#app')

/*
 * access token 主动续期（2026-10-21）。
 *
 * 必须在 mount 之后启动：它内部要用 Pinia store，而 store 在 app.use(pinia)
 * 之后才可用。放在这里而不是 onMounted —— 续期是全局后台任务，
 * 不属于任何组件，挂到某个组件上会因为该组件卸载而停掉。
 *
 * 详见 utils/tokenRenew.js：15 分钟的 access token 若不主动续期，
 * 到期时必然先吃一次 401；而 401 刷新在多通道并发下会互相吊销 refresh，
 * 表现为「莫名被踢去登录页」。
 */
import { startTokenRenew } from './utils/tokenRenew'
startTokenRenew()

// 全局拉一次当前用户信息:无论刷新、新开标签还是直接进首页,
// 都保证 userStore.userInfo 有值(否则顶栏头像/个人主页/主页按钮全部失效)。
// 只在有 token(已登录)时拉,失败静默(拦截器已处理过期跳登录)。
const userStore = useUserStore()
if (userStore.hasToken) {
  userStore.fetchMe().catch(() => {})
}