/**
 * 未定义标识符检查(漏 import / 用错名字 / 未声明变量)
 * ------------------------------------------------------------------
 * 存在的理由:`MyReviewBlock.vue` 用 `ref([])` 却没从 'vue' import ref,
 * 运行时报 `ReferenceError: ref is not defined` → 个人主页白屏。
 *
 * 它逃过了全部现有门禁:
 * - `check-sfc.mjs` 走 compileScript,只做语法转换**不做作用域分析** ——
 *   实测它对这段代码**编译通过**,报「编译器零提醒」。
 * - `verify-frontend.py` 扫硬编码/emoji/断点,不查标识符是否存在。
 * - `check-template-refs.mjs` 查模板引用,不查 script 里的裸标识符。
 *
 * 也就是说:**一个会让页面白屏的 bug,所有门禁都报「通过」**。
 * 这个脚本把那类问题变成编译期可见。
 *
 * ## 为什么用正则而不是 acorn(已实测,不是拍脑袋)
 *
 * `acorn` 在 node_modules 里(8.18.0,由 rollup/vue 生态传递带入),
 * **但不在 package.json 的 dependencies 里** —— 属于不稳定传递依赖,
 * rollup 换版本可能就没了。按「不为一个规则新增依赖」的要求不引入。
 *
 * 也试过用 `@vue/compiler-sfc` 的 compileScript.bindings 代替(零新增依赖):
 * 实测它只记录**已声明**的绑定(`{ active: 'setup-maybe-ref' }`),
 * **不包含「引用了但没定义」的信息**,且对漏 import 的代码**编译通过**。
 * 所以编译器本身给不出这个判据,只能自己扫。
 *
 * 正则的代价与对策见下方 KNOWN_LIMITS —— 宁可承认局限,不要假装完备。
 *
 * 用法:
 *   node scripts/check-undefined-identifiers.mjs
 *   node scripts/check-undefined-identifiers.mjs --self-test
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseSFC } from '@vue/compiler-sfc'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'src')

/**
 * 编译器宏:由 Vue 编译期注入,**不需要 import**。
 * 检查它们是纯粹的误报来源 —— 项目里 45 个 .vue 几乎都用了 defineProps。
 */
const MACROS = new Set([
  'defineProps', 'defineEmits', 'defineExpose', 'defineOptions', 'defineSlots',
  'defineModel', 'withDefaults', 'useSlots', 'useAttrs',
  // <script setup> 编译产物里的标识符,不该被当漏 import
  '__default__', '__returned__', '__isScriptSetup',
])

/**
 * 组合式 API:必须来自 'vue',缺 import 就是 ReferenceError。
 * 名单来自项目实际用法(grep 过)+ Vue 3 全部公开 API,不是抄文档。
 */
const VUE_APIS = new Set([
  'ref', 'computed', 'reactive', 'readonly', 'shallowRef', 'shallowReactive',
  'watch', 'watchEffect', 'watchPostEffect', 'watchSyncEffect',
  'onMounted', 'onBeforeMount', 'onBeforeUpdate', 'onUpdated',
  'onBeforeUnmount', 'onUnmounted', 'onErrorCaptured', 'onActivated', 'onDeactivated',
  'onRenderTracked', 'onRenderTriggered', 'onServerPrefetch',
  'nextTick', 'toRef', 'toRefs', 'toRaw', 'toValue', 'unref', 'isRef',
  'markRaw', 'customRef', 'effectScope', 'getCurrentScope', 'onScopeDispose',
  'provide', 'inject', 'hasInjectionContext',
  'defineComponent', 'h', 'mergeProps', 'useCssModule', 'useCssVars', 'useAttrs', 'useSlots',
])

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (e.name.endsWith('.vue')) out.push(p)
  }
  return out
}

/** 去掉行注释与块注释,避免注释里的词被当成代码 */
function stripComments(code) {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length))
}

/** 字符串与模板字面量里的内容不算代码 */
function stripStrings(code) {
  return code
    .replace(/`(?:[^`\\]|\\.)*`/g, (m) => '`'.repeat(m.length))
    .replace(/'(?:[^'\\\n]|\\.)*'/g, (m) => "'".repeat(m.length))
    .replace(/"(?:[^"\\\n]|\\.)*"/g, (m) => '"'.repeat(m.length))
}

/**
 * 已声明的名字:import 的 + 顶层声明的(变量/函数/类)+ 编译器宏。
 *
 * 只认 import 与顶层声明 —— 函数内部声明不该让整个文件「已定义」,
 * 那会让「A 函数里用了 ref、A 没 import ref」这种真 bug 溜过去。
 */
function collectDeclared(code) {
  const declared = new Set(MACROS)

  // import { a, b as c } from '...'  /  import d from '...'  /  import * as ns
  const importRe = /import\s+(?:([\w$]+)\s*,\s*)?(?:\{([^}]*)\}|\*\s+as\s+([\w$]+)|([\w$]+))?\s*from/g
  let m
  while ((m = importRe.exec(code))) {
    if (m[1]) declared.add(m[1])
    if (m[2]) {
      for (const part of m[2].split(',')) {
        const t = part.trim()
        if (!t) continue
        const asSplit = t.split(/\s+as\s+/)
        declared.add((asSplit[1] || asSplit[0]).trim())
      }
    }
    if (m[3]) declared.add(m[3])
    if (m[4]) declared.add(m[4])
  }

  // 顶层声明:const/let/var/function/class,以及 import() 动态引入的解构
  const declRe = /^\s*(?:const|let|var)\s+([\w$]+)|^\s*(?:async\s+)?function\s+([\w$]+)|^\s*class\s+([\w$]+)/gm
  while ((m = declRe.exec(code))) {
    declared.add(m[1] || m[2] || m[3])
  }
  // 多变量解构 const { a, b } = x / const [a, b] = x
  const multiRe = /^\s*(?:const|let|var)\s*[\[{]([^\]}]*)[\]}]/gm
  while ((m = multiRe.exec(code))) {
    for (const part of m[1].split(',')) {
      const t = part.trim().split(':').pop().split('=')[0].trim()
      if (/^[\w$]+$/.test(t)) declared.add(t)
    }
  }
  // 函数参数(顶层函数):function foo(a, b) {}
  const paramRe = /(?:^|\))\s*(?:async\s+)?([\w$]+)\s*\(([^)]*)\)\s*\{/gm
  while ((m = paramRe.exec(code))) {
    for (const part of (m[2] || '').split(',')) {
      const t = part.trim().split('=')[0].trim()
      if (/^[\w$]+$/.test(t)) declared.add(t)
    }
  }
  return declared
}

/**
 * 找出「用了但没声明」的 Vue API,返回带行号的清单。
 * 只扫 script setup(与运行时抛错的范围一致 —— Options API 里
 * 漏 import 也一样是 ReferenceError,但本项目已全面用 script setup,
 * 范围外的漏报风险另记在 KNOWN_LIMITS)。
 */
export function scanCode(code) {
  const cleaned = stripStrings(stripComments(code))
  const declared = collectDeclared(cleaned)
  const hits = []
  const lines = cleaned.split('\n')
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]
    for (const name of VUE_APIS) {
      if (declared.has(name)) continue
      if (MACROS.has(name)) continue
      // 「调用」或「引用」形态:ref( / ref = / ref, / ref}
      // 不能只匹配标识符本身 —— 那会把 obj.ref、import { ref } 误报成使用。
      const re = new RegExp(`(?<![.\\w$])${name}\\s*(\\(|\\)|,|=|;|\\]|\\}|\\s*\\.)`, 'g')
      if (re.test(line)) {
        hits.push({ name, line: i + 1, text: line.trim().slice(0, 90) })
      }
    }
  }
  return hits
}

/**
 * 已知局限(写出来是为了下一个人知道边界在哪,而不是误以为它完备):
 *
 * 1. **只覆盖 Vue 组合式 API**,不查业务标识符。`const a = notDefined()` 这类
 *    同样会白屏,但不在这条规则的范围内 —— 要覆盖需要真正的作用域分析。
 * 2. **不区分作用域**。函数 A 内的局部 `ref` 遮蔽了全局未 import 的 `ref`
 *    时,本规则**可能误报**。判据是全文件级declared 集合。
 *    误报方向是「多查」,不是「漏查」—— 可以接受(报错需人工确认一行)。
 * 3. **只扫 `<script setup>`**。Options API(`export default {}`)里的漏 import
 *    不查。本项目已无 Options API 写法,但将来引入会形成盲区。
 * 4. 动态引入 `const { ref } = await import('vue')` 会被误报 —— 写法罕见,
 *    真遇到了加白名单即可。
 */
export const KNOWN_LIMITS = [
  '只覆盖 Vue 组合式 API,不查业务标识符',
  '不区分作用域,局部遮蔽可能误报',
  '只扫 <script setup>,Options API 不覆盖',
  '动态 import 解构可能误报',
]

/* ────────────  正常模式  ──────────── */
function run() {
  const files = walk(SRC)
  let total = 0
  const all = []
  for (const f of files) {
    const src = fs.readFileSync(f, 'utf8')
    const { descriptor } = parseSFC(src, { filename: f })
    const code = descriptor.scriptSetup?.content
    if (!code) continue
    /**
     * 行号偏移用编译器给的 `loc.start.line`,不用 `src.indexOf(code)` 自己算。
     *
     * 踩过的坑:indexOf 版本算出 offset=1,于是 `const active = ref([])`
     * (真实第 101 行)被报成第 44 行 —— **报错的行号指向另一个地方,
     * 照着查会查错**。SFC 解析会规范化空白,indexOf 本身也不可靠。
     * `descriptor.scriptSetup.loc.start.line` 是编译器算的真实起始行。
     */
    const startLine = descriptor.scriptSetup.loc.start.line
    for (const h of scanCode(code)) {
      // 注意:...h 必须放前面。放后面会用 h.line 覆盖掉算好的行号 ——
      // 实测踩过:输出恒为 scriptSetup 内的相对行号(44),而不是文件里的真实行号(101)。
      // 行号指向另一个地方,照着查会查错。
      all.push({ ...h, file: path.relative(ROOT, f).replace(/\\/g, '/'), line: h.line + startLine - 1 })
      total += 1
    }
  }

  console.log(`检查 ${files.length} 个 .vue 的 <script setup>`)
  if (total === 0) {
    console.log('所有 Vue 组合式 API 都有对应的 import 或声明')
    console.log(`(已知局限:${KNOWN_LIMITS.length} 条,见脚本头注释)`)
    return 0
  }
  console.log(`\n发现 ${total} 处未定义标识符:`)
  for (const h of all) {
    console.log(`  ${h.file}:${h.line}  '${h.name}'  ${h.text}`)
  }
  console.log(`\n这类问题在运行时是 ReferenceError,逃过了 check-sfc.mjs(compileScript 不做作用域分析)。`)
  return total
}

/* ────────────  自检:门禁必须能抓到已知的坏  ──────────── */
async function selfTest() {
  console.log('自检:验证门禁能抓到漏 import,且不误报正常文件\n')
  const cases = [
    {
      label: '漏 import ref(应抓到)',
      code: `const active = ref([])\nconst x = computed(() => 1)\n`,
      expect: ['ref', 'computed'],
    },
    {
      label: '有 import(不应报)',
      code: `import { ref, computed } from 'vue'\nconst active = ref([])\nconst x = computed(() => 1)\n`,
      expect: [],
    },
    {
      label: '只有宏 defineProps,无 import(不应报宏)',
      code: `const props = defineProps({ a: String })\nconst emit = defineEmits(['x'])\n`,
      expect: [],
    },
    {
      label: '本地 import 自定义 composable 里的 ref(不应报)',
      code: `import { useData } from '@/composables/useData'\nconst d = useData()\n`,
      expect: [],
    },
    {
      label: '注释与字符串里的 ref 不算(不应报)',
      code: `// 这里提到 ref 但没 import\nconst s = 'ref([]) 和 computed'\nconst t = \`ref 也是\`\n`,
      expect: [],
    },
    {
      label: '别名 import(as)后可用(不应报)',
      code: `import { ref as r, computed as c } from 'vue'\nconst a = r([])\nconst b = c(() => 1)\n`,
      expect: [],
    },
    {
      label: '局部遮蔽会误报(已知局限,记录真实行为)',
      code: `function f(ref) { return ref() }\n`,
      expect: ['ref'],
      knownFalsePositive: true,
    },
  ]

  let bad = 0
  for (const c of cases) {
    const hits = scanCode(c.code).map((h) => h.name)
    const missing = c.expect.filter((n) => !hits.includes(n))
    const unexpected = hits.filter((n) => !c.expect.includes(n))
    // 已知局限那条只要求「行为与记录一致」,不要求通过
    const ok = c.knownFalsePositive ? hits.length === c.expect.length : (missing.length === 0 && unexpected.length === 0)
    if (!ok) bad += 1
    const note = c.knownFalsePositive ? '  (记录局限,不算失败)' : ''
    console.log(`  [${ok ? ' ok ' : 'FAIL'}] ${c.label.padEnd(40)} 期望=[${c.expect}] 实际=[${hits}]${note}`)
    if (missing.length) console.log(`         漏抓: ${missing.join(', ')}`)
    if (unexpected.length) console.log(`         误报: ${unexpected.join(', ')}`)
  }
  console.log(`\n自检 ${cases.length - bad}/${cases.length} 符合预期`)
  if (bad === 0) console.log('判据有效:已知漏 import 能抓到,正常写法不误报')
  return bad
}

if (process.argv.includes('--self-test')) {
  const bad = await selfTest()
  process.exitCode = bad === 0 ? 0 : 1
} else {
  const n = run()
  process.exitCode = n === 0 ? 0 : 1
}