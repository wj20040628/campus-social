/**
 * composable 解构契约检查
 * ------------------------------------------------------------------
 * 查的是这一类:组件里从某个 composable 的返回值解构了一个名字,
 * 而该 composable 的 return 里没有这个名字。
 *
 * [为什么必须单独写一个脚本]
 * 这是编译期不报、运行时才出错的一类:
 *   - `check-sfc.mjs` 的 compileScript 只看单文件,看不到跨文件的 return
 *   - `check-template-refs.mjs` 只扫模板标识符,解构在 script 里,扫不到
 *   - Vite / rollup 也不报:解构一个不存在的 export 只是拿到 undefined
 *
 * 后果不是「白屏」那么明显,而是**行为静默出错**。实际踩到过两种:
 *   1. `reset is not a function` —— 一按 J/K 就抛,好在有堆栈
 *   2. 更糟的一类:界面用 A 的值、逻辑用 B 的值,两者不一致且全程无报错,
 *      管理员只会觉得「这软件偶尔反应慢」
 *
 * [为什么用 acorn 而不是正则]
 * 这一版是第三版。前两版都用正则,各失败一次,两次都是**假通过**
 * (报「全部通过」但其实一处都没检查):
 *   - 第一版:解构分段时把逗号当分隔符吃掉,键集合恒为空 → 165 条全误报,
 *     误报量本身就是「检查坏了」的信号,但我第一次没当回事
 *   - 第二版:`[^{}]*` 匹配不了跨行解构,而本项目为了过 300 行门禁
 *     几乎所有解构都是跨行排的 → 灵敏度验证直接失败
 *   - 第三版:靠 `[(;,]` 收尾匹配赋值右侧,实际代码是 `= q` 后面跟空行
 *     → 匹配到 0 处,又是一次假通过
 * 正则解析 JS 的代价就是这些。acorn 是 vite 已有的传递依赖,
 * 直接 import 即可,**不需要往 package.json 加依赖**。
 *
 * [已知边界 —— 有意不覆盖,避免给出「查过了」的错觉]
 *   1. **成员访问式取值不查**:`const a = useXxx().a`。
 *      项目里 Pinia store 走的是这个写法(`const userStore = useUserStore()`
 *      后 `userStore.hasToken`),没有对象解构,所以本来就不在检查范围内 ——
 *      store 的契约由 TS 与后端接口保证,前端这边没有静态检查。
 *   2. **跨文件传递不查**:`const { a } = useFoo()` 后把 a 传给别的 composable。
 *   3. composable 函数体内部的 return 才认;模块顶层 `export const x = {...}`
 *      这类不是 composable,不查(本项目 composable 一律是 `export function useXxx`)。
 *
 * [关于输出的一个教训]
 * 第一版我在头部注释里写了「未检查的函数名会在输出里显式列出」,
 * 但实现里那个集合被拼成了 `let skippedFn` 又在别处读,分支从没进过 ——
 * **注释里的承诺没有被兑现,而输出看起来完全正常**。
 * team-lead 加 --verbose 复跑发现没列出来。
 * 所以现在这条承诺有对应的灵敏度验证:把解构源改指向 `useUserStore()`,
 * 输出里必须出现 `useUserStore`,核对处数必须从 22 降到 21。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseJS } from 'acorn'
import { parse as parseSFC } from '@vue/compiler-sfc'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'src')

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|js)$/.test(e.name)) out.push(p)
  }
  return out
}

/** acorn 解析,统一按 module 源码;失败返回 null(不猜) */
function tryParse(code) {
  try {
    return parseJS(code, { ecmaVersion: 2022, sourceType: 'module', locations: true })
  } catch {
    return null
  }
}

/** 遍历 AST */
function walkNode(node, visit) {
  if (!node || typeof node.type !== 'string') return
  visit(node)
  for (const k of Object.keys(node)) {
    if (k === 'type' || k === 'start' || k === 'end' || k === 'loc') continue
    const v = node[k]
    if (Array.isArray(v)) v.forEach((c) => walkNode(c, visit))
    else if (v && typeof v === 'object') walkNode(v, visit)
  }
}

/**
 * composable 函数名 -> return 的顶层键集合。
 *
 * 按 `export function useXxx` 的函数体边界取 return,
 * 不能对整个文件取「最后一个 return」—— 一个 composable 文件里通常还有
 * 别的导出(如 `export const CODE_COOLDOWN = 60`),抓错会让所有键变空集。
 */
const composableReturns = new Map()

for (const f of walk(SRC)) {
  if (!f.includes(`${path.sep}composables${path.sep}`) || !f.endsWith('.js')) continue
  const ast = tryParse(fs.readFileSync(f, 'utf8'))
  if (!ast) continue

  walkNode(ast, (n) => {
    if (n.type !== 'FunctionDeclaration' || !n.id || !n.id.name.startsWith('use')) return
    let keys = null
    walkNode(n.body, (b) => {
      if (b.type !== 'ReturnStatement' || !b.argument) return
      const arg = b.argument
      // 只认对象字面量 return。返回变量/条件表达式认不出,置 null = 不查,
      // 宁可漏报也不误报(误报多了没人看门禁)。
      if (arg.type !== 'ObjectExpression') { keys = null; return }
      const set = new Set()
      for (const prop of arg.properties) {
        if (prop.type !== 'Property') continue
        // 缩写 `{ a }` 与显式 `{ a: x }` 取的都是 key
        if (prop.key.type === 'Identifier') set.add(prop.key.name)
        else if (prop.key.type === 'Literal') set.add(String(prop.key.value))
      }
      keys = set
    })
    if (keys) composableReturns.set(n.id.name, keys)
  })
}

const problems = []
let checked = 0
/** 被引用到但无法核对 return 的函数名(非 composable/ 目录下的工厂函数,如 Pinia store) */
const unverified = new Set()

for (const file of walk(SRC)) {
  if (!file.endsWith('.vue')) continue
  const source = fs.readFileSync(file, 'utf8')
  const { descriptor } = parseSFC(source, { filename: file })
  const code = descriptor.scriptSetup?.content || descriptor.script?.content
  if (!code) continue
  const ast = tryParse(code)
  if (!ast) continue

  /**
   * 第一步:变量 -> composable 映射。
   * `const q = useReportQueue(...)` 后 `const { a } = q` 是本项目主流写法
   * (composable 常返回二十几个键,直接解构会顶破 300 行门禁)。
   */
  const varToFn = new Map()
  walkNode(ast, (n) => {
    if (n.type !== 'VariableDeclarator' || !n.init || n.init.type !== 'CallExpression') return
    const callee = n.init.callee
    if (callee.type !== 'Identifier' || !callee.name.startsWith('use')) return
    if (n.id.type !== 'Identifier') return
    varToFn.set(n.id.name, callee.name)
  })

  walkNode(ast, (n) => {
    if (n.type !== 'VariableDeclarator' || n.id.type !== 'ObjectPattern') return
    const src = n.init
    if (!src) return
    // 来源是标识符(接了一层)或直接调用
    const fn = src.type === 'Identifier' ? varToFn.get(src.name)
      : src.type === 'CallExpression' && src.callee.type === 'Identifier' ? src.callee.name
      : null
    if (!fn || !fn.startsWith('use')) return
    if (!composableReturns.has(fn)) { unverified.add(fn); return }
    const keys = composableReturns.get(fn)
    checked += 1
    for (const prop of n.id.properties) {
      if (prop.type !== 'Property') continue
      // 解构侧取 key(原名):`{ mentionKw: keyword }` 里 mentionKw 才是
      // return 里的那个名字,keyword 只是本地别名。
      if (prop.key.type !== 'Identifier') continue
      if (!keys.has(prop.key.name)) {
        const line = code.slice(0, prop.start).split('\n').length
        problems.push(
          `${path.relative(ROOT, file).replace(/\\/g, '/')}:${line} ` +
          `从 ${fn}() 解构了「${prop.key.name}」,但该 composable 的 return 里没有它`,
        )
      }
    }
  })
}

// 两个数字都是**实时数出来的**,不要写成「已登记 N 个」——
// 那是登记表的规模,不是检查覆盖面,而且登记逻辑一改它就会漂移。
// lead 实测 src/composables/ 有 28 个 .js 文件,而脚本只报 27,
// 差的 1 个是 chatWsHandlers.js(不含 use* 函数,确实没有 return 可核对)。
// 报 27 会让人以为漏了一个文件。
const composableFiles = walk(SRC).filter(
  (f) => f.includes(`${path.sep}composables${path.sep}`) && f.endsWith('.js'),
).length

console.log(`解构契约检查 ${checked} 处 / ${composableFiles} 个 composable 文件`)
if (unverified.size) {
  // 显式说明哪些没查。静默跳过 = 让人以为查过了。
  // 这一条曾经是写在注释里但没实现的承诺(集合名被写成 `skippedFn` 又在
  // 另一处读,分支从没进过),team-lead 复跑发现没列出来。
  // 承诺要么兑现要么删掉,不能留在注释里当已实现的特性。
  console.log(`  未核对 return(该函数不在 composables/ 下): ${[...unverified].sort().join(', ')}`)
} else {
  console.log('  所有对象解构均已核对 return')
}
if (problems.length) {
  console.log(`\n发现 ${problems.length} 个问题:`)
  for (const p of problems) console.log('  - ' + p)
  process.exitCode = 1
} else {
  console.log('解构契约检查通过')
}
