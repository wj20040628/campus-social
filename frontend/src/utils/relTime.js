/**
 * 列表副行的相对时间(圈子卡片「最近动态」用)。
 *
 * 入参是 CircleVO.lastPostAt,后端格式化成 "yyyy-MM-dd HH:mm" 的字符串
 * (VO 里刻意不用 LocalDateTime,绕开 VO 的 datetime 序列化口径,前端只做相对时间)。
 * 所以这里先手工补 T 再 Date.parse —— Safari 对 "yyyy-MM-dd HH:mm" 解析出 NaN,
 * 对 "yyyy-MM-ddTHH:mm" 正常,这是全站相对时间的统一口径。
 *
 * 返回空串表示「没有帖子」,由调用方接「还没有帖子」的文案,
 * 这里不产出完整句子 —— 句式(有没有「有新帖」尾巴)属于展示层。
 */
export function relTime(s) {
  if (!s) return ''
  const t = new Date(String(s).replace(' ', 'T')).getTime()
  if (Number.isNaN(t)) return ''

  const now = Date.now()
  const diff = now - t
  if (diff < 0) return '刚刚'

  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`

  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} 小时前`

  // 「昨天」按日历日算而不是 24h:23:50 发的帖,00:10 看到的应该是「昨天」
  const day = Math.floor((startOfToday(now) - startOfToday(t)) / 86400000)
  if (day <= 1) return '昨天'
  if (day <= 7) return `${day} 天前`

  const d = new Date(t)
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日`
}

function startOfToday(ms) {
  const d = new Date(ms)
  d.setHours(0, 0, 0, 0)
  return d.getTime()
}
