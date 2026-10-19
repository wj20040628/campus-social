/**
 * ElMessageBox 的全局依赖补丁(2026-10-18)
 * ===================================================================
 * ## 现象
 * 点「退出登录」「取消报名」「移除预留名」等所有走 `ElMessageBox.confirm`
 * 的地方,弹窗**完全不出现** —— 表现为「点了没反应」。而站内所有
 * `ElMessage` 提示都正常。
 *
 * ## 根因(实测,不是猜)
 * EP 的 MessageBox 是**运行时 `render()` 出来的**,不是编译期 import,
 * 所以它的模板里用的是 `resolveComponent("el-overlay")` 这类**按名字查全局注册**:
 *
 *   node_modules/element-plus/es/components/message-box/src/index.mjs
 *     resolveComponent("el-button") / ("el-focus-trap") / ("el-icon")
 *     resolveComponent("el-input")  / ("el-overlay")
 *
 * 而本项目为了压首屏体积(TD-21:整包 `app.use(ElementPlus)` 曾把 element-plus
 * 打成 943KB / gzip 303KB)早把它删了,改成 `unplugin-vue-components` 按需引入 ——
 * **模板里的 `<el-xxx>` 才会自动 import,`app.component()` 里一个都没有**。
 *
 * `resolveComponent` 找不到就回退成字符串 `"el-overlay"`,
 * Vue 把它当**未知 HTML 标签**渲染 → 弹窗根节点是空的 `<el-overlay>`,
 * 遮罩、对话框、按钮全都不存在。控制台只有一条
 * "Failed to resolve component: el-overlay" 的警告,肉眼完全看不出发生了什么。
 *
 * 对照证据:`message/src/message2.mjs` 里 `resolveComponent` 出现 **0 次**
 * (全部编译期直接 import),所以 ElMessage 一切正常 —— 只有 MessageBox 挂。
 *
 * ## 为什么不能靠 `app.use(ElMessageBox)` 解决
 * `install()` 只做了两件事:设 `_context`、挂 `$msgbox` 等全局属性。
 * 它**不注册组件** —— 组件仍要我们手动 `app.component()`。
 * 所以两件事都得做,少一件还是白屏。
 *
 * ## 体积代价
 * 下面 5 个组件里,`ElButton` / `ElInput` / `ElIcon` 站内本来就在用
 * (按需引入已经打进各自的 chunk),`ElOverlay` 是所有 `el-dialog` 的底座,
 * 只有 `ElFocusTrap` 是纯新增 —— 它是个不到 2KB 的焦点陷阱工具。
 * 与「恢复整包注册」相比,这个方案的体积代价可以忽略。
 *
 * ## 副作用样式
 * `element-plus/es/components/message-box/style/css.mjs` 已经连带引了
 * base/input/button/overlay 四份样式(EP 自己在那个文件里 import 的),
 * 这里只补 EP 没引的 focus-trap(它无样式)与 icon 的样式入口,
 * 避免弹层里的图标 / 输入框在未走到对应页面时是「无样式裸元素」。
 */
import ElOverlay from 'element-plus/es/components/overlay/index.mjs'
import 'element-plus/es/components/overlay/style/css.mjs'
import ElFocusTrap from 'element-plus/es/components/focus-trap/index.mjs'
import ElButton from 'element-plus/es/components/button/index.mjs'
import 'element-plus/es/components/button/style/css.mjs'
import ElInput from 'element-plus/es/components/input/index.mjs'
import 'element-plus/es/components/input/style/css.mjs'
import ElIcon from 'element-plus/es/components/icon/index.mjs'
import 'element-plus/es/components/icon/style/css.mjs'
import { ElMessageBox } from '@/utils/feedback'

/**
 * 注册 MessageBox 依赖的全局组件,并把 app 上下文交给 MessageBox。
 *
 * @param {import('vue').App} app main.js 里 createApp 出来的实例
 */
export function patchMessageBox(app) {
  // resolveComponent 查的是 appContext.components,所以必须真注册
  app.component('ElOverlay', ElOverlay)
  app.component('ElFocusTrap', ElFocusTrap)
  app.component('ElButton', ElButton)
  app.component('ElInput', ElInput)
  app.component('ElIcon', ElIcon)

  // MessageBox 自己也是 render 出来的,没有 _context 就拿不到 app 的
  // provides / config(全局配置、暗色、locale 都在这里)。两件事缺一不可。
  ElMessageBox._context = app._context
}

export default patchMessageBox
