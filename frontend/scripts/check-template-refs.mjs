/**
 * 模板标识符静态检查
 * ------------------------------------------------------------------
 * 查的是**编译期不报错、运行时才炸**的一类问题:模板里引用了一个
 * script 没定义的变量。Vue 的模板编译不做作用域分析(模板与 script 编译到
 * 同一个作用域的渲染函数),所以写错了照样编译通过,
 * 只有用户点下去才在控制台报 "xxx is not defined"。
 *
 * ## 已知的检查边界(有意不为,避免给出「查过了」的错觉)
 *
 * 1. **不解析 JS 表达式语义**。正则扫描只能覆盖扁平引用;
 *    条件表达式里嵌套的解构、动态属性等不保证覆盖。
 * 2. **defineProps 未赋给变量时**,props 的名字直接可在模板用,
 *    本脚本从 `defineProps({...})` 的键里取。
 * 3. **组件标签名不查**(如 <ProfileHead />):那是 import,不是标识符引用。
 * 4. **全局/自动导入的名字**按白名单放过,白名单外的全局变量会误报 ——
 *    这是刻意的:宁可多报一条让人看一眼,不要静默漏掉真问题。
 */
import { readFileSync, readdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from '@vue/compiler-sfc'

/**
 * ROOT/SRC 定位:与 check-sfc / check-composable-exports / check-undefined-identifiers
 * 同口径 —— 按脚本所在目录算,不依赖调用方 CWD。
 *
 * 旧写法 `walk('src')` 假设 CWD=frontend/,从 frontend/ 之外(如项目根)直接
 * `node frontend/scripts/check-template-refs.mjs` 会 ENOENT。
 * `run-all-gates.py` 的 step 是从项目根调 node 的,必须脚本自带定位。
 */
const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const ROOT = join(SCRIPT_DIR, '..')
const SRC = join(ROOT, 'src')

/** Vue/EP 在本项目里可全局或自动导入的名字 */
const GLOBALS = new Set([
  'ref', 'computed', 'reactive', 'readonly', 'watch', 'watchEffect', 'nextTick',
  'onMounted', 'onBeforeUnmount', 'onUnmounted', 'onActivated', 'onDeactivated',
  'storeToRefs', 'defineProps', 'defineEmits', 'defineExpose', 'defineOptions',
  'ElMessage', 'ElMessageBox', 'ElNotification', 'ElLoading',
  // 模板里以 $ 开头的实例属性(不是 script 绑定)
  '$emit', '$event', '$refs', '$el', '$props', '$attrs', '$slots', '$nextTick',
])

/** 表达式关键字。不是自由引用 */
const KEYWORDS = new Set([
  'true', 'false', 'null', 'undefined', 'typeof', 'instanceof', 'in', 'of', 'new',
  'return', 'if', 'else', 'void', 'delete', 'this', 'Math', 'JSON', 'Object',
  'Array', 'String', 'Number', 'Boolean', 'Date', 'parseInt', 'parseFloat',
  'isNaN', 'encodeURIComponent', 'decodeURIComponent',
])

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    if (entry.isDirectory()) walk(p, acc)
    else if (entry.name.endsWith('.vue')) acc.push(p)
  }
  return acc
}

function collectBindings(code) {
  const names = new Set()
  const add = (raw) => {
    const t = raw.trim().split(/\s+as\s+/).pop().trim()
    if (t && t !== 'as') names.add(t)
  }
  for (const m of code.matchAll(/(?:const|let|var|function)\s+([A-Za-z0-9_$]+)/g)) names.add(m[1])
  for (const m of code.matchAll(/(?:const|let|var)\s*\{([^}]*)\}\s*=/g)) {
    m[1].split(',').forEach((part) => add(part.includes(':') ? part.split(':')[1] : part))
  }
  for (const m of code.matchAll(/import\s*\{([^}]*)\}\s*from/g)) m[1].split(',').forEach(add)
  for (const m of code.matchAll(/import\s+([A-Za-z0-9_$]+)\s*(?:,|from)/g)) names.add(m[1])
  return names
}

/**
 * 模板里可用的 props 名。
 *
 * 两种写法都要认:
 *   defineProps({ post: {...} })            → props 直接可在模板用
 *   const props = defineProps({ post: {} })  → **props 的键**可在模板用,
 *                                             变量名 props 本身不暴露
 * 第二种形式很容易被漏掉:模板里写 post.xxx 是对的,写 props.post 是错的,
 * 所以这里收集的是键名而不是变量名。
 */
function collectPropNames(code) {
  const names = new Set()
  const idx = code.indexOf('defineProps')
  if (idx === -1) return names
  const open = code.indexOf('{', idx + 'defineProps'.length)
  if (open === -1) return names
  let depth = 0
  for (let i = open; i < code.length; i += 1) {
    const ch = code[i]
    if (ch === '{') { depth += 1; continue }
    if (ch === '}') {
      depth -= 1
      if (depth === 0) break
      continue
    }
    // 顶层(depth===1)的 props 键有两种写法:裸标识符 title: {...}
    // 与引号包裹的 'title': {...}
    if (depth === 1) {
      if (/[A-Za-z_$]/.test(ch)) {
        const m = code.slice(i).match(/^([A-Za-z0-9_$]+)\s*:/)
        if (m) {
          names.add(m[1])
          i += m[0].length - 1
        }
      } else if (/['"]/.test(ch)) {
        const m = code.slice(i + 1).match(/^([A-Za-z0-9_$]+)['"]\s*:/)
        if (m) {
          names.add(m[1])
          i += m[0].length
        }
      }
    }
  }
  return names
}

/** v-for="(item, i) in list" 里的循环变量 */
function collectVForVars(tpl) {
  const names = new Set()
  for (const m of tpl.matchAll(/v-for="([^"]*)"/g)) {
    // 取 " in " 左边那段,再剥掉外层括号
    const left = m[1].split(/\s+in\s+/)[0] || ''
    for (const seg of left.split(',')) {
      const t = seg.trim().replace(/^\(|\)$/g, '').replace(/^\(|\)$/g, '').trim()
      if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(t)) names.add(t)
    }
  }
  return names
}

/**
 * 插槽作用域变量:`<slot #head="{ foo }" />` 或
 * `<router-view v-slot="{ Component }">` 里解构出来的名字,
 * 由插槽提供方注入,不是本组件 script 的绑定。不认它们会误报
 * (PasswordInput 的 `visible`、MainLayout 的 `Component` 都栽在这)。
 */
function collectSlotScopeVars(tpl) {
  const names = new Set()
  for (const m of tpl.matchAll(/(?:v-slot|#[\w-]+)="\{([^}]*)\}"/g)) {
    for (const part of m[1].split(',')) {
      const t = part.trim().split(/\s+as\s+/).pop().trim()
      if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(t)) names.add(t)
    }
  }
  return names
}

/**
 * 内联箭头函数的参数:模板里常见 `(ids) => onAddMembers(ids)` /
 * `info?.members?.map((m) => m.user?.id)`。这些参数是表达式局部的,
 * 不是 script 的绑定,不能当成「未声明」去报。
 *
 * 只处理带括号的箭头(本项目模板里都是这种写法);解构参数
 * `{ id }` / `[a, b]` 与无括号单参 `a =>` 极少见,先不特殊处理 ——
 * 真要出现会误报一条,但那也比静默漏掉好(本脚本的设计偏好是多报)。
 */
function collectArrowParams(expr) {
  const params = new Set()
  for (const m of expr.matchAll(/\(([^()]*)\)\s*=>/g)) {
    for (const part of m[1].split(',')) {
      let t = part.trim().split('=')[0].trim() // 去掉默认值 a = 1
      t = t.replace(/^\.\.\./, '').trim() // 去掉 rest ...
      if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(t)) params.add(t)
    }
  }
  return params
}

/**
 * 剥掉字符串字面量、成员访问、对象字面量的键,只留「自由标识符」。
 *
 * 三处容易误报、必须剥掉的东西:
 *   1. `$emit` / `$event` —— 开头的 $ 不是单词字符,`\b` 会从 e 开始匹配,
 *      于是 `$emit` 被截成 `emit`、`$event` 被截成 `event`。
 *   2. 对象字面量的键 —— `:style="{ width: '40%' }"` 里的 width 是键不是变量,
 *      `:autosize="{ minRows: 1 }"` 同理。不剥的话满屏都是假阳性。
 *   3. 内联箭头参数(见 collectArrowParams)—— `(ids) => onAddMembers(ids)`
 *      里的 ids 是参数不是自由变量,要从结果里剔除,否则实打实存在的
 *      箭头参数全会被误报成「未声明」。
 */
function freeIdentifiers(expr) {
  // 去掉 '...' "..." `...`(先去字符串,否则里面的 // 会被当成注释)
  let s = expr.replace(/'[^']*'/g, "''").replace(/"[^"]*"/g, '""').replace(/`[^`]*`/g, '``')
  // 去掉表达式内的 JS 注释:模板属性里常有 `:class="{ // 注释 }"` 这种行内注释,
  // 不剥会把注释里的字(如 chip)误报成未声明变量。字符串已先去掉,这里 // 不会是 https://
  s = s.replace(/\/\*[\s\S]*?\*\//g, ' ')
  s = s.split('\n').map((l) => l.replace(/\/\/.*$/, '')).join('\n')
  // 去掉 obj.prop / obj?.prop 的 prop 部分:把 . 后面的标识符抹成 _
  s = s.replace(/\.\s*([A-Za-z_$][A-Za-z0-9_$]*)/g, '._')
  // 去掉对象字面量的键:{ k: v, 'k2': v } 与解构别名 { k: alias }
  s = s.replace(/([{,]\s*)(['"]?)([A-Za-z_$][A-Za-z0-9_$]*)\2\s*:/g, '$1_:')
  const skip = collectArrowParams(expr)
  const out = new Set()
  // 标识符前面若是 $($emit/$event),跳过 —— 那是 Vue 实例属性,不是自由变量
  for (const m of s.matchAll(/(^|[^\w$])([A-Za-z_$][A-Za-z0-9_$]*)\b/g)) {
    const name = m[2]
    if (name === '_' || skip.has(name)) continue
    out.add(name)
  }
  return out
}

/**
 * 指令/事件表达式。
 *
 * **2026-10-15 修了一个让本门禁整体失效的正则错误**:
 * 原写法 `(?:^|\s)(?::|v-if=|...|@[\w.:-]+=|v-bind:)="([^"]*)"` ——
 * 分支 `@[\w.:-]+=` 已经吃掉了 `=`,后面又跟了一个 `="`,
 * 等于要求文本是 `@click==`。结果**全项目的 @click="fn()" 一条都没被扫过**,
 * 而 v-if / v-bind 这类分支因为不带尾部 `=` 反而能命中,
 * 门禁一直报「模板标识符检查通过」,给人已经查过这个维度的错觉。
 * (正是这个洞放过了一次真实事故:某组件拆出后模板里残留父级的
 *  onLongPress(m) / expand(m.id),编译不报、运行才炸。)
 *
 * 现在把 `=` 统一放在分支外面,并要求分支本身不带 `=`。
 */
function collectExprs(tpl) {
  const exprs = []
  for (const m of tpl.matchAll(/\{\{([\s\S]*?)\}\}/g)) exprs.push(m[1])
  const dirRe = /(?:^|\s)(?::[\w.-]+|v-[\w:-]+|@[\w.:-]+)\s*=\s*"([^"]*)"/g
  for (const m of tpl.matchAll(dirRe)) exprs.push(m[1])
  return exprs
}

const files = walk(SRC)
let suspect = 0

for (const file of files) {
  const rel = file.replace(/\\/g, '/').replace(ROOT.replace(/\\/g, '/') + '/', '')
  const source = readFileSync(file, 'utf8')
  const { descriptor } = parse(source, { filename: file })
  if (!descriptor.template) continue

  const code = descriptor.scriptSetup?.content || descriptor.script?.content || null
  if (!code) continue

  // 先剥 HTML 注释:注释里的字(如「chip 块」「el-page-header」)不是表达式,
  // 不剥会误报。副作用:注释里若真有 `{{ x }}` 也不会查 —— 可接受,注释里的
  // 插值本来就不该参与渲染。
  const tpl = descriptor.template.content.replace(/<!--[\s\S]*?-->/g, '')
  const known = new Set([
    ...collectBindings(code),
    ...collectPropNames(code),
    ...collectVForVars(tpl),
    ...collectSlotScopeVars(tpl),
  ])

  const missing = new Set()
  for (const expr of collectExprs(tpl)) {
    for (const name of freeIdentifiers(expr)) {
      if (KEYWORDS.has(name) || GLOBALS.has(name) || known.has(name)) continue
      missing.add(name)
    }
  }

  if (missing.size) {
    suspect += 1
    console.log(`- ${rel}\n    模板引用但 script 未声明: ${[...missing].join(', ')}`)
  }
}

console.log(suspect ? `\n发现 ${suspect} 个文件有可疑引用` : '模板标识符检查通过')
process.exit(suspect ? 1 : 0)