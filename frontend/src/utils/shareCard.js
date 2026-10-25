/**
 * 分享卡片 canvas 绘制(2026-10-25 分享卡片功能)。
 *
 * 为什么前端画而不是后端合成:后端没有 QR/合成依赖(引入 zxing 是新增后端依赖),
 * 而卡片是纯展示物,前端 canvas 一次搞定,还天然复用当前域名。
 *
 * 画布不用 CSS 变量:canvas 2d context 读不到 var(),这里按 design-tokens
 * 的值硬编码(与 design-tokens.css 同源,改 token 记得同步这里)。
 */
import QRCode from 'qrcode'
import { resolveUrl } from '@/utils/resolve'

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
const H = 1150
const PAD = 44

/**
 * 画一张分享卡片。
 *
 * @param {{kind:'post'|'mkt'|'activity', title:string, excerpt?:string,
 *          authorName?:string, topics?:string[], image?:string,
 *          qrText:string, meta?:string}} opt
 *   title   卡片主标题(帖子取正文前段,活动取活动名)
 *   excerpt 帖子正文(与 title 分开时用;帖子场景 title=excerpt 首行)
 *   image   可选封面图 url(相对 /uploads 也行,内部走 resolveUrl;
 *           加载失败(跨域/文件缺失)自动跳过,不阻塞出图)
 *   qrText  二维码内容,调用方传 window.location.origin + 路由
 * @returns {Promise<HTMLCanvasElement>}
 */
export async function drawShareCard(opt) {
  const dpr = Math.min(2, window.devicePixelRatio || 1)
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
  // 品牌点缀:右上角一个小圆
  ctx.beginPath()
  ctx.arc(W - PAD - 16, 78, 16, 0, Math.PI * 2)
  ctx.fillStyle = C.accentSoft
  ctx.fill()

  // 内容白卡
  const cardY = 150
  const cardH = H - cardY - 300
  roundRect(ctx, PAD, cardY, W - PAD * 2, cardH, 28)
  ctx.fillStyle = C.surface
  ctx.fill()
  ctx.strokeStyle = C.border
  ctx.lineWidth = 2
  ctx.stroke()

  let y = cardY + 56
  const innerX = PAD + 44
  const innerW = W - PAD * 2 - 88

  // 作者/来源行(匿名帖由调用方传「匿名」)
  if (opt.authorName) {
    ctx.fillStyle = C.accent
    ctx.font = '500 26px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    ctx.fillText(ellipsize(ctx, opt.authorName, innerW), innerX, y)
    y += 56
  }

  // 主文案:标题加粗 + 正文常规,统一走逐字换行(CJK 无空格,按字符断行)
  const titleFont = '600 38px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  const bodyFont = '400 30px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  y = drawWrapped(ctx, opt.title || '', innerX, y, innerW, titleFont, C.fg, 42, 4)
  if (opt.excerpt) {
    y = drawWrapped(ctx, opt.excerpt, innerX, y + 10, innerW, bodyFont, C.fg2, 44, 7)
  }

  // 话题 chips:单行排,放不下截断
  if (opt.topics?.length) {
    y += 26
    let x = innerX
    ctx.font = '400 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
    for (const t of opt.topics.slice(0, 3)) {
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

  // 封面图(可选):等比缩放填宽,最多占卡内剩余高度的一半
  if (opt.image) {
    const img = await loadImageSafe(opt.image)
    if (img) {
      const maxH = Math.min(360, cardY + cardH - y - 44)
      if (maxH > 120) {
        const scale = Math.min(innerW / img.width, maxH / img.height)
        const dw = img.width * scale
        const dh = img.height * scale
        ctx.save()
        roundRect(ctx, innerX, y, innerW, dh, 16)
        ctx.clip()
        // 等比铺满裁切:按目标框绘制
        const fillScale = Math.max(innerW / img.width, dh / img.height)
        const fw = img.width * fillScale
        const fh = img.height * fillScale
        ctx.drawImage(img, innerX + (innerW - fw) / 2, y + (dh - fh) / 2, fw, fh)
        ctx.restore()
        y += dh + 20
      }
    }
  }

  // 底部:二维码 + 引导文案(固定位置,不随内容流)
  const qrSize = 240
  const qrDataUrl = await QRCode.toDataURL(opt.qrText, {
    width: qrSize * 2,
    margin: 1,
    color: { dark: C.fg, light: '#ffffff' },
  })
  const qrImg = await loadImageSafe(qrDataUrl)
  const qrX = (W - qrSize) / 2
  const qrY = H - 288
  roundRect(ctx, qrX - 14, qrY - 14, qrSize + 28, qrSize + 28, 18)
  ctx.fillStyle = C.surface
  ctx.fill()
  if (qrImg) ctx.drawImage(qrImg, qrX, qrY, qrSize, qrSize)

  ctx.textAlign = 'center'
  ctx.fillStyle = C.fg
  ctx.font = '500 30px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText(opt.meta || '扫码查看完整内容', W / 2, qrY + qrSize + 64)
  ctx.fillStyle = C.muted
  ctx.font = '400 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText(hostOf(opt.qrText), W / 2, qrY + qrSize + 106)
  ctx.textAlign = 'left'

  return canvas
}

/** 逐字换行绘制(CJK 场景),返回下一行基线 y。maxLines 截断,超出加省略号 */
function drawWrapped(ctx, text, x, y, maxWidth, font, color, lineHeight, maxLines) {
  ctx.font = font
  ctx.fillStyle = color
  const chars = [...String(text)]
  let line = ''
  let lines = 0
  for (const ch of chars) {
    if (ch === '\n') {
      ctx.fillText(line, x, y)
      y += lineHeight
      lines += 1
      line = ''
      if (lines >= maxLines) break
      continue
    }
    if (ctx.measureText(line + ch).width > maxWidth) {
      ctx.fillText(line, x, y)
      y += lineHeight
      lines += 1
      line = ch
      if (lines >= maxLines) {
        line = ''
        break
      }
    } else {
      line += ch
    }
  }
  if (line && lines < maxLines) {
    ctx.fillText(ellipsize(ctx, line, maxWidth), x, y)
    y += lineHeight
  } else if (!line && lines >= maxLines) {
    // 截断的那一行画省略号提示
  }
  return y
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

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

function hostOf(url) {
  try {
    return new URL(url).host
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
