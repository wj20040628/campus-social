/**
 * 校验 README.md 里引用的本地资源是否真实存在。
 *
 * 【为什么需要】
 * Markdown 的图片/链接写错路径不会报错，只会静默显示成裂图或死链 ——
 * 在 GitHub 上要肉眼翻到才能发现。这个脚本把「引用」与「磁盘」对一遍。
 */
import fs from 'node:fs'
import path from 'node:path'

const md = fs.readFileSync('README.md', 'utf8')

// 收集 markdown 图片/链接，以及 HTML 的 src/href
const refs = new Set()
for (const m of md.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g)) refs.add(m[1])
for (const m of md.matchAll(/(?:src|href)="([^"]+)"/g)) refs.add(m[1])

const local = [...refs].filter(
  (u) => !/^(https?:|mailto:|#)/.test(u),
)

let bad = 0
const ok = []
for (const u of local) {
  const clean = u.split('#')[0]
  if (!clean) continue
  const p = path.resolve(decodeURIComponent(clean))
  if (fs.existsSync(p)) ok.push(clean)
  else {
    console.log('  ✗ 缺失:', clean)
    bad++
  }
}

console.log(`\n本地引用 ${local.length} 个，存在 ${ok.length} 个，缺失 ${bad} 个`)
process.exit(bad ? 1 : 0)
