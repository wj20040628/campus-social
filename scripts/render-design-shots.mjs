/**
 * 把 docs/design/*.html 设计稿渲染成 PNG，供 README 内嵌与人工查阅。
 *
 * 【为什么要渲染】
 * GitHub / 多数 Markdown 渲染器都会过滤 HTML，README 里直接写 <iframe> 或
 * 内联 HTML 不生效；能显示出来的只有图片。所以设计稿以图片形式进 README，
 * 同时保留一条指向原始 HTML 的链接（本地打开可看完整交互稿）。
 *
 * 【为什么全页截图】
 * 这些稿子是「一页多屏」的长图结构（并排对比、清单罗列），只截首屏会丢掉大半内容。
 */
import { chromium } from 'file:///F:/%E6%A0%A1%E5%9B%AD%E7%A4%BE%E4%BA%A4/.e2e-env/node_modules/playwright/index.mjs'
import fs from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const SRC = 'docs/design'
const OUT = 'docs/design/shots'
fs.mkdirSync(OUT, { recursive: true })

const files = fs.readdirSync(SRC).filter((f) => f.endsWith('.html')).sort()
const browser = await chromium.launch({ channel: 'chrome' })

for (const f of files) {
  const name = f.replace(/\.html$/, '')
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  // pathToFileURL 会正确处理中文与空格（手工拼 file:// 在这里必错）
  await page.goto(pathToFileURL(path.resolve(SRC, f)).href, { waitUntil: 'load' })
  await page.waitForTimeout(400)
  const h = await page.evaluate(() => document.documentElement.scrollHeight)
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true })
  const kb = Math.round(fs.statSync(`${OUT}/${name}.png`).size / 1024)
  console.log(`  ${name}.png  ${h}px  ${kb}KB`)
  await ctx.close()
}

await browser.close()
console.log(`\n共渲染 ${files.length} 张 → ${OUT}/`)