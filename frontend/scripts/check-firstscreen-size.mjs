/**
 * AC-17 首屏 JS 体积护栏：入口 chunk + 其静态 import 闭包，gzip 合计 < 300KB。
 *
 * 【为什么不用「dist 下所有 .js 加起来」】
 * 那是最初 CI 里的写法，它把**懒加载**的 chunk 也算进去了，
 * 于是数字被 aliyun-oss-sdk(184KB) / el-date-picker(29KB) 这类
 * 只在特定操作时才下载的分包撑到 894KB，判定必然失败。
 *
 * 而 AC-17 的原文（SPEC.md §9）是：
 *   「When 以移动端 4G 条件加载首页，LCP 必须 < 2.0s，**首屏 JS** 必须 < 300KB gzip」
 * 首屏 JS = 浏览器为了渲染首屏必须先下载并执行的那部分，
 * 也就是从 index.html 的 <script> 出发、沿**静态** import 能走到的一切。
 *
 * 动态 import() 是懒加载，不计入 —— 这正是代码分割的意义所在。
 *
 * 用法（在 frontend/ 下，且已 npm run build）：
 *   node scripts/check-firstscreen-size.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

const BUDGET_KB = 300
const DIST = 'dist'

if (!fs.existsSync(path.join(DIST, 'index.html'))) {
  console.error(`未找到 ${DIST}/index.html —— 请先 npm run build`)
  process.exit(2)
}

// 1) 入口：index.html 里所有 <script src>
const html = fs.readFileSync(path.join(DIST, 'index.html'), 'utf8')
const entries = [...html.matchAll(/<script[^>]+src="([^"]+)"/g)].map((m) =>
  m[1].replace(/^\//, ''),
)
if (!entries.length) {
  console.error('index.html 里没有找到 <script src>，无法判定首屏体积')
  process.exit(2)
}

// 2) 沿静态 import 递归收集。只跟相对路径（裸模块名在构建产物里不会出现）
const seen = new Set()
const rows = []
let total = 0

function resolveImport(fromFile, spec) {
  if (!spec.startsWith('.')) return null
  const base = path.join(path.dirname(fromFile), spec)
  for (const cand of [base, `${base}.js`, path.join(base, 'index.js')]) {
    if (fs.existsSync(cand) && fs.statSync(cand).isFile()) return cand
  }
  return null
}

function walk(file) {
  if (!file || seen.has(file) || !fs.existsSync(file)) return
  seen.add(file)
  const bytes = fs.readFileSync(file)
  const gz = zlib.gzipSync(bytes).length
  total += gz
  rows.push([file.replace(/\\/g, '/'), Math.round(gz / 1024)])

  /*
   * 构建产物里的静态引入有两种形态，都要抓：
   *   import{a}from"./x.js"   /   import"./x.js"   /   export{a}from"./x.js"
   * 用 from 子句覆盖前者，再用 import"..." 覆盖纯副作用引入。
   * 动态 import() 写作 import("./x.js")，带括号，上面的正则不会误伤。
   */
  const src = bytes.toString('utf8')
  const specs = new Set()
  for (const m of src.matchAll(/\bfrom\s*["'](\.[^"']+)["']/g)) specs.add(m[1])
  for (const m of src.matchAll(/\bimport\s*["'](\.[^"']+)["']/g)) specs.add(m[1])
  for (const s of specs) walk(resolveImport(file, s))
}

for (const e of entries) walk(path.join(DIST, e))

// 3) 输出（按体积降序，方便一眼看出谁是大头）
rows.sort((a, b) => b[1] - a[1])
console.log('首屏静态依赖链：')
for (const [f, kb] of rows) console.log(`  ${String(kb).padStart(5)} KB  ${f}`)

const kb = Math.round(total / 1024)
console.log(`\n首屏 JS 合计 gzip: ${kb} KB  (AC-17 预算 ${BUDGET_KB} KB)`)

if (kb > BUDGET_KB) {
  console.error(`超出 AC-17 预算 ${kb - BUDGET_KB} KB`)
  console.error('提示：若新增的是懒加载分包，它不该出现在上面这条链里；')
  console.error('      若确实进了首屏链，考虑对该模块改用动态 import()。')
  process.exit(1)
}
console.log('达标 ✓')
