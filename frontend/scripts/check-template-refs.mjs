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
import { join } from 'node:path'
import { parse } from '@vue/compiler-sfc'

/** Vue/EP 在本项目里可全局或自动导入的名字 */
const GLOBALS = new Set([
  'ref', 'computed', 'reactive', 'readonly', 'watch', 'watchEffect', 'nextTick',
  'onMounted', 'onBeforeUnmount', 'onUnmounted', 'onActivated', 'onDeactivated',
  'storeToRefs', 'defineProps', 'defineEmits', 'defineExpose', 'defineOptions',
  'ElMessage', 'ElMessageBox', 'ElNotification', 'ElLoading',
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

/** 剥掉字符串字面量与成员访问,只留「自由标识符」 */
function freeIdentifiers(expr) {
  // 去掉 '...' "..." `...`
  let s = expr.replace(/'[^']*'/g, "''").replace(/"[^"]*"/g, '""').replace(/`[^`]*`/g, '``')
  // 去掉 obj.prop / obj?.prop 的 prop 部分:把 . 后面的标识符抹成 _(_ 是本脚本的占位符,不参与判断)
  s = s.replace(/\.\s*([A-Za-z_$][A-Za-z0-9_$]*)/g, '._')
  const out = new Set()
  for (const m of s.matchAll(/\b([A-Za-z_$][A-Za-z0-9_$]*)\b/g)) {
    if (m[1] !== '_') out.add(m[1])
  }
  return out
}

function collectExprs(tpl) {
  const exprs = []
  for (const m of tpl.matchAll(/\{\{([\s\S]*?)\}\}/g)) exprs.push(m[1])
  const dirRe = /(?:^|\s)(?::|v-if=|v-else-if=|v-for=|v-show=|v-model=|@[\w.:-]+=|v-bind:)="([^"]*)"/g
  for (const m of tpl.matchAll(dirRe)) exprs.push(m[1])
  return exprs
}

const files = walk('src')
let suspect = 0

for (const file of files) {
  const source = readFileSync(file, 'utf8')
  const { descriptor } = parse(source, { filename: file })
  if (!descriptor.template) continue

  const code = descriptor.scriptSetup?.content || descriptor.script?.content || null
  if (!code) continue

  const tpl = descriptor.template.content
  const known = new Set([
    ...collectBindings(code),
    ...collectPropNames(code),
    ...collectVForVars(tpl),
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
    console.log(`- ${file}\n    模板引用但 script 未声明: ${[...missing].join(', ')}`)
  }
}

console.log(suspect ? `\n发现 ${suspect} 个文件有可疑引用` : '模板标识符检查通过')
process.exit(suspect ? 1 : 0)