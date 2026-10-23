/**
 * 为 README 拍一组「统一、可展示」的真实界面截图。
 *
 * 【为什么不直接用 .design-shots/】
 * 那里有 230 张，绝大多数是排查过程中的中间产物（故障态、对比图、局部放大）。
 * README 需要的是每个页面最正常的一帧，且尺寸/倍率一致 —— 混着放会让版面很脏。
 * 所以这里按固定清单重拍一份，落到 docs/screens/。
 */
import { chromium } from 'file:///F:/%E6%A0%A1%E5%9B%AD%E7%A4%BE%E4%BA%A4/.e2e-env/node_modules/playwright/index.mjs'
import fs from 'node:fs'

const adm = JSON.parse(fs.readFileSync('.e2e-admin.json', 'utf8').replace(/^\uFEFF/, ''))
const OUT = 'docs/screens'
fs.mkdirSync(OUT, { recursive: true })

/** 手机端清单：README 主展示形态（这个产品是移动优先） */
const MOBILE = [
  ['home', '/', '首页动态'],
  ['circles', '/circles', '圈子广场'],
  ['circle-detail', '/circles/41', '圈子详情'],
  ['circle-members', '/circles/41/members', '圈子成员'],
  ['activity', '/activity/28', '活动详情'],
  ['mkt', '/mkt', '集市广场'],
  ['publish', '/publish', '发动态'],
  ['chat', '/chat', '私信'],
  ['profile', '/user/1', '个人主页'],
  ['notify', '/notify', '通知'],
]

/** 桌面端清单：只放最能体现双栏设计的几页 */
const DESKTOP = [
  ['home-desktop', '/', '首页（桌面）'],
  ['circle-detail-desktop', '/circles/41', '圈子详情（桌面双栏）'],
  ['mkt-desktop', '/mkt', '集市（桌面）'],
]

const browser = await chromium.launch({ channel: 'chrome' })

async function shoot(list, vp, mobile) {
  for (const [name, path, label] of list) {
    const ctx = await browser.newContext({
      viewport: { width: vp.w, height: vp.h },
      deviceScaleFactor: 2,
      isMobile: mobile,
      hasTouch: mobile,
    })
    const page = await ctx.newPage()
    await page.addInitScript((a) => {
      localStorage.setItem('access_token', a.tokens.accessToken)
      localStorage.setItem('refresh_token', a.tokens.refreshToken)
      localStorage.setItem('user_info', JSON.stringify(a.user))
    }, adm)

    await page.goto('http://127.0.0.1:5173' + path, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(2800)

    // 风险提示弹窗会挡住内容，先关掉
    const risk = await page.$('.risk__ok')
    if (risk) {
      await risk.click()
      await page.waitForTimeout(500)
    }

    /*
     * 只截首屏（视口），不截整页。
     *
     * 【为什么】README 的图库是并排网格，行高由该行最高的图决定。
     * 整页截图高度差异极大（首页能滚十几屏、发动态只有一屏），
     * 结果就是高的把行撑得很高、矮的下面留一大片空白，网格参差不齐。
     * 统一截视口后每张都是 390×844（桌面 1440×900），排出来才是整齐的九宫格。
     */
    await page.screenshot({ path: `${OUT}/${name}.png` })
    const kb = Math.round(fs.statSync(`${OUT}/${name}.png`).size / 1024)
    console.log(`  ${name}.png  ${label}  ${kb}KB`)
    await ctx.close()
  }
}

console.log('手机端 (390×844)：')
await shoot(MOBILE, { w: 390, h: 844 }, true)
console.log('\n桌面端 (1440×900)：')
await shoot(DESKTOP, { w: 1440, h: 900 }, false)

await browser.close()
console.log(`\n完成 → ${OUT}/`)
