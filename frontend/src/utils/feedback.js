/**
 * ElMessage / ElMessageBox 的按需引入出口(TD-21 的延伸)
 * ------------------------------------------------------------------
 *
 * ## 为什么不能 `import { ElMessage } from '@/utils/feedback'`
 *
 * `unplugin-vue-components` + `ElementPlusResolver` 只处理**模板里的
 * `<el-*>` 标签**。代码里的显式 import 走 Rollup 的静态分析,
 * 它看到 `element-plus` 这个包入口,就把整个包的 index.js 拖进来。
 *
 * 实测后果(CircleDetail.vue):该页 chunk 从约 7 kB 膨胀到 **118.52 kB**,
 * 因为一句 `ElMessage.error()` 把整个 Element Plus 内联了进去。
 * 首屏 index chunk 因 MainLayout 也在用 ElMessage,同样被拖大。
 *
 * ## 为什么这里统一封一个文件而不是各页直接引
 *
 * 21 处 `from 'element-plus'` 分散在 21 个文件里,改一次要改 21 处,
 * 漏一处就退化回 118 kB。收口到一个文件后:
 *   - 各页只 `import { ElMessage } from '@/utils/feedback'`
 *   - 排查时 grep 这一个文件即可确认是否退化
 *   - 将来 EP 换版本/换引入路径只改一处
 *
 * ## 路径是实测出来的,不是猜的
 *
 * `element-plus/es/components/message` 只导出 ElMessage;
 * ElMessageBox 在**另一个**组件目录 `message-box` 下。
 * 两个都从包入口导只是因为包入口做了再导出,按需引入时必须分开引。
 * (核验方式:node -e "import('element-plus/es/components/message/index.mjs').then(m=>console.log(Object.keys(m)))")
 *
 * ## 路径必须带 .mjs(实测踩过的坑)
 *
 * EP 的 package.json exports 声明了 `"./es/*": { "import": "./es/*.mjs" }`,
 * 所以 `element-plus/es/components/message` 会被解析成
 * `element-plus/es/components/message.mjs` —— 该文件不存在,构建直接失败。
 * 正确写法是带后缀的完整文件名:
 *   element-plus/es/components/message/index.mjs
 *
 * 另外 Node 的 import() 能 resolve 无后缀路径(它会补 .mjs),
 * 但 Vite/Rollup 走的是 exports 字段,行为不同 ——
 * **核验依赖写法时要以 vite build 为准,不能只看 node -e 能跑**。
 *
 * ## 副作用样式必须手动引
 *
 * 按需引入组件本体不包含它的 CSS。style/css.mjs 是编译好的 css 文件,
 * 引入顺序在 main.js 的 element-plus 样式之后、design-tokens.css 之前,
 * 保证 design-tokens.css 的 Token 能覆盖它(CSS 变量后定义者胜)。
 */
import { ElMessage } from 'element-plus/es/components/message/index.mjs'
import 'element-plus/es/components/message/style/css.mjs'
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs'
import 'element-plus/es/components/message-box/style/css.mjs'

export { ElMessage, ElMessageBox }

export default { ElMessage, ElMessageBox }
