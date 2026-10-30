/**
 * 分享卡片的通用绘制件(2026-10-30 从 shareCard.js 拆出)。
 *
 * 只放与卡片内容无关的纯 canvas 绘制：圆角矩形、视频播放键/角标/占位块。
 * shareCard.js 负责排版与文字；这里保证视频帖在卡片上「看得出是视频」。
 */

export function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

/** 视频封面上的「视频」角标 + 居中播放键 */
export function drawVideoBadge(ctx, x, y, w, h) {
  ctx.save()
  const label = '视频'
  ctx.font = '500 24px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  const pillW = ctx.measureText(label).width + 28
  roundRect(ctx, x + 18, y + 18, pillW, 44, 22)
  ctx.fillStyle = 'rgba(20, 20, 20, 0.58)'
  ctx.fill()
  ctx.fillStyle = '#ffffff'
  ctx.fillText(label, x + 32, y + 48)
  drawPlayGlyph(ctx, x + w / 2, y + h / 2, 46)
  ctx.restore()
}

/** 没有 poster 的旧视频：画一块深色 16:9 占位，至少让人看出这是视频 */
export function drawVideoPlaceholder(ctx, x, y, w, h) {
  ctx.save()
  const grad = ctx.createLinearGradient(x, y, x + w, y + h)
  grad.addColorStop(0, '#24313a')
  grad.addColorStop(1, '#0e7490')
  roundRect(ctx, x, y, w, h, 16)
  ctx.fillStyle = grad
  ctx.fill()
  drawPlayGlyph(ctx, x + w / 2, y + h / 2 - 12, 46)
  ctx.textAlign = 'center'
  ctx.fillStyle = 'rgba(255, 255, 255, 0.92)'
  ctx.font = '500 26px -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif'
  ctx.fillText('视频', x + w / 2, y + h / 2 + 80)
  ctx.textAlign = 'left'
  ctx.restore()
}

function drawPlayGlyph(ctx, cx, cy, radius) {
  ctx.save()
  ctx.beginPath()
  ctx.arc(cx, cy, radius, 0, Math.PI * 2)
  ctx.fillStyle = 'rgba(20, 20, 20, 0.55)'
  ctx.fill()
  const s = radius * 0.42
  ctx.beginPath()
  ctx.moveTo(cx - s * 0.62, cy - s)
  ctx.lineTo(cx + s, cy)
  ctx.lineTo(cx - s * 0.62, cy + s)
  ctx.closePath()
  ctx.fillStyle = '#ffffff'
  ctx.fill()
  ctx.restore()
}
