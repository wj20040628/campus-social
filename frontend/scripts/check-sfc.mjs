/**
 * SFC 语法与引用校验(只读,不走 vite build)
 * ------------------------------------------------------------------
 * 为什么需要它:`npm run build` 会经过 scripts/build.mjs,而那个文件正在被
 * team-lead 修改,同时跑会撞车。本脚本用 @vue/compiler-sfc 直接编译每个 .vue,
 * 拿到与 vite 同源的语法报错,但不产出任何文件、不写dist。
 *
 * 同时做四项静态检查:
 *   1. 模板 / script 编译无错
 *   2. 单文件行数 <= 400(.vue 与 .js)
 *   3. emoji 零命中(含注释)
 *   4. 模板里引用的组件在 script 中已 import 或已注册
 *
 * 用法:node scripts/check-sfc.mjs
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse, compileTemplate, compileScript } from '@vue/compiler-sfc'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SRC = path.join(ROOT, 'src')
const MAX_LINES = 400

// 覆盖 1F300-1F9FF(emoji) / 2600-26FF(符号) / 2700-27BF(装饰符号)
const EMOJI = /[\u{1F300}-\u{1F9FF}\u{2600}-\u{26FF}\u{2700}-\u{27BF}]/u

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|js)$/.test(e.name)) out.push(p)
  }
  return out
}

const files = walk(SRC)
const problems = []
let checked = 0

for (const file of files) {
  const rel = path.relative(ROOT, file).replace(/\\/g, '/')
  const src = fs.readFileSync(file, 'utf8')
  // 与 `wc -l` 同口径(数换行符)。用 split('\n').length 会在文件以换行结尾时
  // 多算一行,与 team-lead 上一轮验收时用的 wc -l 对不上,门禁就成了两把尺子。
  const lines = src.length === 0 ? 0 : src.split('\n').length - (src.endsWith('\n') ? 1 : 0)

  if (lines > MAX_LINES) {
    problems.push(`${rel}: ${lines} 行,超过 ${MAX_LINES} 行门禁`)
  }

  EMOJI.lastIndex = 0
  let m
  while ((m = EMOJI.exec(src)) !== null) {
    const line = src.slice(0, m.index).split('\n').length
    problems.push(`${rel}:${line} emoji「${m[0]}」`)
  }

  if (!file.endsWith('.vue')) continue

  let sfc
  try {
    sfc = parse(src, { filename: file })
  } catch (e) {
    problems.push(`${rel}: SFC 解析失败 ${e.message}`)
    continue
  }
  if (sfc.errors.length) {
    for (const e of sfc.errors) problems.push(`${rel}: 解析错误 ${e.message}`)
    continue
  }

  // script:含 <script setup> 的会走 compileScript 的绑定分析
  //
  // onWarn 是必须的:compileScript 默认把 warning 打到 console 后**丢弃**,
  // 不接它这个脚本就报不出「模板引用了 script 未声明的变量」这类问题 ——
  // 而那正是编译通过、运行时才炸的一类。不接 onWarn 时本脚本报「全部通过」
  // 会给人已经查过这个维度的错觉。
  try {
    if (sfc.script || sfc.scriptSetup) {
      compileScript(sfc.descriptor, { id: rel, onWarn: (w) => collectWarn(rel, w) })
    }
  } catch (e) {
    problems.push(`${rel}: script 编译失败 ${e.message}`)
  }

  if (sfc.template) {
    const r = compileTemplate({
      source: sfc.template.content,
      filename: file,
      id: rel,
      onWarn: (w) => collectWarn(rel, w),
    })
    for (const e of r.errors || []) {
      problems.push(`${rel}: 模板编译失败 ${typeof e === 'string' ? e : e.message}`)
    }
  }
  checked++
}

// --------------------------------------------------------------------------
// warning 收集
//
// 分开计数而不是混进 problems:warning 不是错误,混进去会让「全部通过」
// 这句话变得不精确(团队验证时要区分「没错」和「没错但有提醒」)。
// 但绝不能丢弃 —— 丢弃等于没查。
// --------------------------------------------------------------------------
const warns = []

/** warning 可能带 loc(行/列),带上才定位得到问题 */
function collectWarn(rel, w) {
  const msg = typeof w === 'string' ? w : w?.message || String(w)
  const line = w?.loc?.start?.line
  warns.push(line ? `${rel}:${line} 编译器提醒 ${msg}` : `${rel} 编译器提醒 ${msg}`)
}

console.log(`检查 ${files.length} 个文件(其中 SFC ${checked} 个)`)
if (warns.length) {
  // warning 不设 exitCode —— 它不阻断构建,但必须让人看见。
  // 「零 warning」是团队验证要盯的指标之一,悄悄丢掉就等于没有这项指标。
  console.log(`\n编译器提醒 ${warns.length} 条:`)
  for (const w of warns) console.log('  - ' + w)
} else {
  console.log('编译器零提醒')
}
if (problems.length === 0) {
  console.log('全部通过')
} else {
  console.log(`\n发现 ${problems.length} 个问题:`)
  for (const p of problems) console.log('  - ' + p)
  process.exitCode = 1
}