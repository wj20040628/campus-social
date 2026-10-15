/**
 * 外置样式检查（2026-10-15 新增）
 * ------------------------------------------------------------------
 * 查两类「编译期不报错、运行才炸 / 样式静默失效」的问题:
 *
 * 1. SFC 里 `<style src="X.css">` 指向的文件不存在。
 *    项目采用「组件样式外置」模式(与 views/*.css 同一套),门禁重构时
 *    从 .vue 抽样式到 .css 是高频操作。一旦文件名写错 / 路径拼错,
 *    vite 直接报 "Internal Server Error",整页白屏;但 check-sfc 只编译
 *    .vue 不解析 src 引用,查不到。
 *
 * 2. 外置 .css 文件括号不平衡。
 *    抽样式时最容易漏抽一段(尤其 @media 末尾那个独立的 `}`),漏了之后
 *    **静默失效**——后面的规则全被吞进那个没闭合的块里,页面某块样式
 *    凭空消失,但没有任何报错。这就是之前 SettingsProfile.css 那次的事故。
 *
 * 做法:
 *   - 扫 src 下所有 .vue 的 `<style src>`,逐一验「文件存在」。
 *     (存在性只有这里能查;.css 不存在时下面的 .css 遍历根本看不到它。)
 *   - 扫 src 下所有 .css 做「括号平衡」(双保险,覆盖「直接改坏 .css」)。
 *     为避免重复上报,被 .vue 引用的 .css 的括号平衡统一由 .css 遍历负责。
 *
 * 字符串字面量里的 `{` / `}`(如 content: "{") 先剔掉,避免假阳性。
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url))
const ROOT = join(SCRIPT_DIR, '..')
const SRC = join(ROOT, 'src')

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name)
    if (entry.isDirectory()) walk(p, acc)
    else if (/\.(vue|css)$/.test(entry.name)) acc.push(p)
  }
  return acc
}

const relOf = (p) => p.replace(/\\/g, '/').replace(ROOT.replace(/\\/g, '/') + '/', '')

/** 去掉字符串字面量,避免 content: "{" 之类的假阳性;遇 `}` 先于 `{` 也判错 */
function braceBalanced(css) {
  const s = css.replace(/'[^']*'/g, "''").replace(/"[^"]*"/g, '""').replace(/`[^`]*`/g, '``')
  let depth = 0
  for (const ch of s) {
    if (ch === '{') depth += 1
    else if (ch === '}') {
      depth -= 1
      if (depth < 0) return false // 先出现 },说明前面少了 {
    }
  }
  return depth === 0
}

const files = walk(SRC)
let bad = 0

for (const file of files) {
  const rel = relOf(file)
  if (file.endsWith('.vue')) {
    const src = readFileSync(file, 'utf8')
    for (const m of src.matchAll(/<style[^>]*\bsrc\s*=\s*["']([^"']+)["']/gi)) {
      const ref = m[1]
      const target = join(dirname(file), ref)
      if (!existsSync(target)) {
        bad += 1
        console.log(`- ${rel}\n    外置样式不存在: ${ref}`)
      }
    }
  } else if (file.endsWith('.css')) {
    const css = readFileSync(file, 'utf8')
    if (!braceBalanced(css)) {
      bad += 1
      console.log(`- ${rel}\n    样式括号不平衡（疑似漏抽某段,尤其 @media 末尾的 }）`)
    }
  }
}

console.log(bad ? `\n发现 ${bad} 处外置样式问题` : '外置样式检查通过')
process.exit(bad ? 1 : 0)
