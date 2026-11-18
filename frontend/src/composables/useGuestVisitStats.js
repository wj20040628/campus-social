/**
 * 管理总览·游客 / 扫码区块的展示计算（2026-11-14）。
 *
 * [为什么单独抽出来] AdminOverview.vue 被单文件 400 行门禁卡住，
 * 趋势柱高、来源占比这些是纯展示换算，与页面拉数无关，放这里还能单独盯逻辑。
 *
 * 口径：
 * - 7 日趋势的补零由后端完成，前端只按当周最大值换算百分比高度；
 * - 来源占比的分母是当天游客会话总数，前 6 组（后端 LIMIT 6）之外
 *   补一行「其他来源」，否则占比会被截断放大。
 */
import { computed, unref } from 'vue'

/** 非零最小值：柱高百分比与条形宽度太小时至少留一点可见像素 */
const TREND_MIN_PERCENT = 10
const RANK_MIN_PERCENT = 4

export function useGuestVisitStats(guestVisit) {
  const stats = () => unref(guestVisit) || {}

  const shortRate = computed(() => {
    const g = stats()
    const settled = Number(g.todaySettledSessions || 0)
    if (!settled) return '—'
    return `${Math.round((Number(g.todayShortSessions || 0) / settled) * 100)}%`
  })

  const trendBars = computed(() => {
    const days = Array.isArray(stats().trend) ? stats().trend : []
    const values = days.map((d) => Number(d?.scanVisitors) || 0)
    const max = Math.max(1, ...values)
    return days.map((d, i) => {
      const n = values[i]
      const sessions = Number(d?.scanSessions) || 0
      const date = String(d?.date || '')
      return {
        date,
        n,
        label: date.slice(5).replace('-', '/'),
        tip: `${date}：${n} 位扫码访客 · ${sessions} 次打开`,
        h: n ? `${Math.max(TREND_MIN_PERCENT, Math.round((n / max) * 100))}%` : '2px',
      }
    })
  })

  const trendTotal = computed(() => trendBars.value.reduce((sum, d) => sum + d.n, 0))

  const trendSummary = computed(
    () => '近 7 日扫码访客（人）：' + trendBars.value.map((d) => `${d.label} ${d.n}`).join('，'),
  )

  const sourceRows = computed(() => {
    const list = Array.isArray(stats().sources) ? stats().sources : []
    const rows = list.map((s) => ({ label: sourceLabel(s?.source), n: Number(s?.sessions) || 0 }))
    const shown = rows.reduce((sum, r) => sum + r.n, 0)
    const total = Math.max(Number(stats().todayGuestSessions || 0), shown)
    if (total > shown) rows.push({ label: '其他来源', n: total - shown })
    return rows.map((r, i) => ({
      key: `${i}-${r.label}`,
      ...r,
      pct: total ? `${Math.round((r.n / total) * 100)}%` : '—',
      width: total ? `${Math.max(RANK_MIN_PERCENT, Math.round((r.n / total) * 100))}%` : '0%',
    }))
  })

  return { shortRate, trendBars, trendTotal, trendSummary, sourceRows, duration }
}

/** 来源标签：qr / guest_login / direct 之外是宣传物料自定义的 from 值 */
function sourceLabel(source) {
  if (!source) return '未标注'
  if (source === 'qr') return '扫码直入'
  if (source === 'guest_login') return '登录页'
  if (source === 'direct') return '直接访问'
  return source
}

/** 秒数转「1 时 5 分 / 3 分 / 45 秒」；卡片窄，不显示到秒 */
function duration(sec) {
  const s = Math.max(0, Math.round(Number(sec) || 0))
  if (s < 60) return `${s} 秒`
  const totalMin = Math.round(s / 60)
  if (totalMin < 60) return `${totalMin} 分`
  const h = Math.floor(totalMin / 60)
  const m = totalMin % 60
  return m ? `${h} 时 ${m} 分` : `${h} 时`
}