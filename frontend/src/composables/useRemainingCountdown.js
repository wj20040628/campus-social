import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import dayjs from 'dayjs'

/**
 * 集市活动剩余时长倒计时(秒级)。
 * ------------------------------------------------------------------
 * 对 `expire_time` 每秒重算剩余,渲染成「剩 X」文案;
 * 剩余 ≤ 2h 时返回 warning,让调用方把数字切成橙红(design-token warn/danger)。
 *
 * 为什么不用 useCodeCountdown:那个是固定 N 秒冷却(验证码),
 * 这里是对「目标时刻」的实时剩余,语义不同。
 * 纪律与 useCodeCountdown 相同:卸载必须停表,否则路由来回切几次,
 * 多个 setInterval 同时跑会把剩余拉快,表现成「没到期就显示过期」。
 *
 * @param {import('vue').MaybeRef<string|number>} target expireTime(ISO 串/时间戳)
 * @returns {{ remainingText: import('vue').ComputedRef<string>, warn: import('vue').ComputedRef<boolean>,
 *             expired: import('vue').ComputedRef<boolean>, stop: Function }}
 */
export function useRemainingCountdown(target) {
  const now = ref(Date.now())
  let timer = null

  const endMs = computed(() => {
    const t = target?.value !== undefined ? target.value : target
    const d = dayjs(t)
    return d.isValid() ? d.valueOf() : null
  })

  function tick() {
    now.value = Date.now()
  }

  onMounted(() => {
    tick()
    timer = setInterval(tick, 1000)
  })

  onBeforeUnmount(() => {
    if (timer) clearInterval(timer)
    timer = null
  })

  /** 剩余总毫秒;已过期返回 0 */
  const remainMs = computed(() => {
    if (!endMs.value) return 0
    return Math.max(0, endMs.value - now.value)
  })

  /** 剩余 ≤ 2 小时 → warning(橙红) */
  const warn = computed(() => remainMs.value > 0 && remainMs.value <= 2 * 3600 * 1000)

  /** 是否已到期(用于把「剩 0」显示成「已到期」) */
  const expired = computed(() => !!endMs.value && remainMs.value <= 0)

  /**
   * 渲染文案:
   *   未到点 / 无目标 → ''(卡片不渲染倒计时)
   *   已到期 → '已到期'
   *   否则按 天/时/分/秒 分级,最小粒度 1 秒(计划 §倒计时 1s 间隔)
   */
  const remainingText = computed(() => {
    if (expired.value) return '已到期'
    let s = Math.floor(remainMs.value / 1000)
    if (s <= 0) return remainMs.value <= 0 ? '' : '即将到期'
    const d = Math.floor(s / 86400); s -= d * 86400
    const h = Math.floor(s / 3600); s -= h * 3600
    const m = Math.floor(s / 60); s -= m * 60
    if (d) return `剩 ${d}天 ${h}时`
    if (h) return `剩 ${h}时 ${m}分`
    if (m) return `剩 ${m}分 ${s}秒`
    return `剩 ${s}秒`
  })

  return { remainingText, warn, expired, stop() { if (timer) { clearInterval(timer); timer = null } } }
}

export default useRemainingCountdown
