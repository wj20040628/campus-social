/**
 * 分享卡片 canvas 绘制(2026-10-25 分享卡片功能)。
 *
 * 为什么前端画而不是后端合成:后端没有 QR/合成依赖(引入 zxing 是新增后端依赖),
 * 而卡片是纯展示物,前端 canvas 一次搞定,还天然复用当前域名。
 *
 * 画布不用 CSS 变量:canvas 2d context 读不到 var(),这里按 design-tokens
 * 的值硬编码(与 design-tokens.css 同源,改 token 记得同步这里)。
 *
 * 高度自适应(2026-10-25):先按字体量出每块内容的行数与高度,
 * 白卡与画布总高随之伸缩——短正文不再留半截空白,长正文也不截断。
 * 量高与绘制用同一份行数组(wrapLines 的产物),两遍结果必然一致。
 */
import QRCode from 'qrcode'
import { resolveUrl } from '@/utils/resolve'
import { roundRect, drawVideoBadge, drawVideoPlaceholder } from './shareCardDraw'

const C = {
  bg: '#FBF3E9', // --ys-bg 暖米底
  surface: '#ffffff',
  fg: '#241C15',
  fg2: '#4A4033',
  muted: '#75695A',
  border: '#EADFC9',
  accent: '#0e7490',
  accentSoft: '#e2f0f4',
}

const W = 750
const PAD = 44
const CARD_Y = 150 // 顶部品牌行高度,白卡从这开始
/**
 * 底部二维码区固定高。构成:白卡下间距 34 + 二维码 240(白框 ±14)
 * + 引导文案两行(基线 +64/+106)+ 底边距约 26。
 * 此前是 300,文案基线落在画布外(H+16),「扫码查看完整内容」被裁得只剩字顶。
 */
const QR_SECTION = 420
const CARD_TOP_PAD = 56
const CARD_BOTTOM_PAD = 44
/** 白卡最矮高度:再短的内容也保证留白不局促(低于此值卡片显得瘪) */
const CARD_MIN_H = 360

/**
 * 画一张分享卡片。
 *
 * @param {{kind:'post'|'mkt'|'activity'|'file', title:string, excerpt?:string,
 *          authorName?:string, topics?:string[], image?:string, badge?:string,
 *          qrText:string, meta?:string}} opt
 *   title   卡片主文案(帖子取正文前段,活动取活动名)
 *   excerpt 副文案(集市/活动的详情摘要)
 *   image   可选封面图 url(相对 /uploads 也行,内部走 resolveUrl;
 *           加载失败(跨域/文件缺失)自动跳过,不阻塞出图)
 *   video   可选,true 表示这是视频帖:有封面时叠播放标识,没封面时画视频占位块
 *   badge   可选方形类型徽标文字(如 DOCX)，排在标题左侧；文件分享用
 *   qrText  二维码内容,调用方传 window.location.origin + 路由
 * @returns {Promise<HTMLCanvasElement>}
 */
export async function drawShareCard(opt) {
  const dpr = Math.min(2, window.devicePixelRatio || 1)
  // 量高用的独立 2d context,与正式绘制分离
  const measure = document.createElement('canvas').getContext('2d')

  const titleFont = '600 38px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  const bodyFont = '400 30px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  const innerX = PAD + 44
  const innerW = W - PAD * 2 - 88

  // ---- 第一遍:量出所有内容块,推导白卡高与画布总高 ----
  let contentBottom = CARD_Y + CARD_TOP_PAD
  if (opt.authorName) contentBottom += 56

  /*
   * 类型徽标(2026-10-28,文件分享用):左侧一个方形色块写「DOCX」，
   * 标题排在它右边。文件卡片没有配图，靠这个色块区分「这是一份文件」
   * 而不是一条动态。
   */
  const badge = String(opt.badge || '').trim().toUpperCase().slice(0, 5)
  const BADGE = 104
  const BADGE_GAP = 24
  const titleX = badge ? innerX + BADGE + BADGE_GAP : innerX
  const titleW = badge ? innerW - BADGE - BADGE_GAP : innerW

  const titleLines = wrapLines(measure, opt.title || '', titleW, titleFont, 4)
  const titleH = titleLines.length * 42
  const rowH = badge ? Math.max(titleH, BADGE) : titleH
  contentBottom += rowH

  const excerptLines = opt.excerpt ? wrapLines(measure, opt.excerpt, innerW, bodyFont, 7) : []
  // 与绘制段保持一致：有徽标时副行多留 24（不是 10），否则量出的高度会少 14px
  if (excerptLines.length) contentBottom += (badge ? 24 : 10) + excerptLines.length * 44

  const topics = (opt.topics || []).slice(0, 3)
  if (topics.length) contentBottom += 26 + 46 + 14

  let img = null
  let imgH = 0
  if (opt.image) {
    img = await loadImageSafe(opt.image)
    if (img) {
      // 封面等比缩到卡内容宽,超高(竖图)封顶 360,绘制时居中裁切
      imgH = Math.min(360, Math.round(innerW * (img.height / img.width)))
    }
  }
  const isVideo = opt.video === true
  // 视频没封面(旧帖没 poster / 图挂了)时给一块 16:9 占位,而不是让卡片只剩文字
  const videoBoxH = Math.round((innerW * 9) / 16)
  if (imgH) contentBottom += imgH + 20
  else if (isVideo) contentBottom += videoBoxH + 20

  const cardH = Math.max(CARD_MIN_H, contentBottom + CARD_BOTTOM_PAD - CARD_Y)
  const H = CARD_Y + cardH + QR_SECTION

  // ---- 第二遍:正式绘制 ----
  const canvas = document.createElement('canvas')
  canvas.width = W * dpr
  canvas.height = H * dpr
  const ctx = canvas.getContext('2d')
  ctx.scale(dpr, dpr)

  // 底色 + 顶部品牌行
  ctx.fillStyle = C.bg
  ctx.fillRect(0, 0, W, H)
  ctx.fillStyle = C.accent
  ctx.font = '600 34px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('友声', PAD, 92)
  ctx.fillStyle = C.muted
  ctx.font = '400 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('校园里的声音', PAD + 100, 90)
  ctx.beginPath()
  ctx.arc(W - PAD - 16, 78, 16, 0, Math.PI * 2)
  ctx.fillStyle = C.accentSoft
  ctx.fill()

  // 内容白卡(高度由内容决定)
  roundRect(ctx, PAD, CARD_Y, W - PAD * 2, cardH, 28)
  ctx.fillStyle = C.surface
  ctx.fill()
  ctx.strokeStyle = C.border
  ctx.lineWidth = 2
  ctx.stroke()

  let y = CARD_Y + CARD_TOP_PAD

  if (opt.authorName) {
    ctx.fillStyle = C.accent
    ctx.font = '500 26px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(ellipsize(ctx, opt.authorName, innerW), innerX, y)
    y += 56
  }

  const rowTop = y
  if (badge) {
    roundRect(ctx, innerX, rowTop, BADGE, BADGE, 20)
    ctx.fillStyle = C.accentSoft
    ctx.fill()
    ctx.save()
    ctx.textAlign = 'center'
    ctx.fillStyle = C.accent
    ctx.font = '700 26px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(badge, innerX + BADGE / 2, rowTop + BADGE / 2 + 9)
    ctx.restore()
  }

  ctx.font = titleFont
  ctx.fillStyle = C.fg
  // 有徽标时标题块在徽标高度内垂直居中；无徽标维持原来的顶部对齐
  let ty = badge ? rowTop + (BADGE - titleH) / 2 + 30 : rowTop
  for (const line of titleLines) {
    ctx.fillText(line, titleX, ty)
    ty += 42
  }
  y = rowTop + rowH

  if (excerptLines.length) {
    // 有徽标时多留一点间距：副行紧贴色块底部会显得挤（2026-10-28 实拍）
    y += badge ? 24 : 10
    ctx.font = bodyFont
    ctx.fillStyle = C.fg2
    for (const line of excerptLines) {
      ctx.fillText(line, innerX, y)
      y += 44
    }
  }

  if (topics.length) {
    y += 26
    let x = innerX
    ctx.font = '400 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    for (const t of topics) {
      const label = `#${t}`
      const w = ctx.measureText(label).width + 32
      if (x + w > innerX + innerW) break
      roundRect(ctx, x, y, w, 46, 23)
      ctx.fillStyle = C.accentSoft
      ctx.fill()
      ctx.fillStyle = C.accent
      ctx.fillText(label, x + 16, y + 31)
      x += w + 12
    }
    y += 60
  }

  if (img && imgH) {
    ctx.save()
    roundRect(ctx, innerX, y, innerW, imgH, 16)
    ctx.clip()
    // 等比铺满裁切:按目标框绘制,居中
    const fillScale = Math.max(innerW / img.width, imgH / img.height)
    const fw = img.width * fillScale
    const fh = img.height * fillScale
    ctx.drawImage(img, innerX + (innerW - fw) / 2, y + (imgH - fh) / 2, fw, fh)
    ctx.restore()
    if (isVideo) drawVideoBadge(ctx, innerX, y, innerW, imgH)
    y += imgH + 20
  } else if (isVideo) {
    drawVideoPlaceholder(ctx, innerX, y, innerW, videoBoxH)
    y += videoBoxH + 20
  }

  // 底部:二维码 + 引导文案(固定在画布底,不随内容流)
  const qrSize = 240
  const qrDataUrl = await QRCode.toDataURL(opt.qrText, {
    width: qrSize * 2,
    margin: 1,
    color: { dark: C.fg, light: '#ffffff' },
  })
  const qrImg = await loadImageSafe(qrDataUrl)
  // 以白卡底为基准往下排:34 间距 + 白框 14
  const qrX = (W - qrSize) / 2
  const qrY = CARD_Y + cardH + 34 + 14
  roundRect(ctx, qrX - 14, qrY - 14, qrSize + 28, qrSize + 28, 18)
  ctx.fillStyle = C.surface
  ctx.fill()
  if (qrImg) ctx.drawImage(qrImg, qrX, qrY, qrSize, qrSize)

  ctx.textAlign = 'center'
  ctx.fillStyle = C.fg
  ctx.font = '500 30px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText(opt.meta || '扫码查看完整内容', W / 2, qrY + qrSize + 64)
  // 备案期(IP/localhost 访问)host 为空,此行不渲染,只留引导文案
  const host = hostOf(opt.qrText)
  if (host) {
    ctx.fillStyle = C.muted
    ctx.font = '400 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(host, W / 2, qrY + qrSize + 106)
  }
  ctx.textAlign = 'left'

  return canvas
}

/**
 * 逐字换行(CJK 无空格,按字符断行),返回行数组供量高与绘制共用。
 * maxLines 截断,截断时最后一行补省略号。
 */
function wrapLines(ctx, text, maxWidth, font, maxLines) {
  ctx.font = font
  const chars = [...String(text)]
  const lines = []
  let line = ''
  let truncated = false
  for (let i = 0; i < chars.length; i++) {
    const ch = chars[i]
    if (ch === '\n') {
      lines.push(line)
      line = ''
      if (lines.length >= maxLines) {
        truncated = true
        break
      }
      continue
    }
    if (line && ctx.measureText(line + ch).width > maxWidth) {
      lines.push(line)
      line = ch
      if (lines.length >= maxLines) {
        line = ''
        truncated = true
        break
      }
    } else {
      line += ch
    }
  }
  if (line && lines.length < maxLines) {
    lines.push(line)
  }
  if (truncated && lines.length) {
    lines[lines.length - 1] = ellipsize(ctx, lines[lines.length - 1], maxWidth)
  }
  return lines
}

function ellipsize(ctx, text, maxWidth) {
  if (ctx.measureText(text).width <= maxWidth) return text
  let t = text
  while (t.length > 1 && ctx.measureText(t + '…').width > maxWidth) {
    t = t.slice(0, -1)
  }
  return t + '…'
}

/** crossOrigin 加载,失败返回 null——封面图是锦上添花,不该让整张卡失败 */
function loadImageSafe(url) {
  return new Promise((resolve) => {
    const img = new Image()
    if (!url.startsWith('data:')) img.crossOrigin = 'anonymous'
    img.onload = () => resolve(img)
    img.onerror = () => resolve(null)
    img.src = url
  })
}

function hostOf(url) {
  try {
    const host = new URL(url).host
    /*
     * 备案期不外示主机名(2026-10-25):走 IP/localhost 访问时,
     * 裸地址打在分享卡上既难看又没有信任价值,扫码的人也不靠它判断
     * (微信/QQ 扫码会先弹链接预览)。正式域名部署后此行自动恢复显示。
     */
    if (/^(\d{1,3}(?:\.\d{1,3}){3}|localhost)(:\d+)?$/i.test(host)) {
      return ''
    }
    return host
  } catch {
    return ''
  }
}

/**
 * 出图后的统一出口:优先系统分享(手机),退化下载(桌面)。
 * @returns {Promise<'shared'|'downloaded'|'failed'>}
 */
export async function shareOrDownload(canvas, fileName) {
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'))
  if (!blob) return 'failed'
  const file = new File([blob], fileName, { type: 'image/png' })
  if (navigator.canShare?.({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: fileName })
      return 'shared'
    } catch {
      // 用户取消或系统拒绝,落到下载
    }
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 5000)
  return 'downloaded'
}

/** 二维码/链接用当前站点域名(备案中的 yousheng8.cn 部署后自动正确,不硬编码) */
export function shareUrl(path) {
  return window.location.origin + path
}
