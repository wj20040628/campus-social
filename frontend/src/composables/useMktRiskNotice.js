import { ref } from 'vue'

/**
 * 集市活动详情 · 安全须知弹窗的开关与「本次登录不再提醒」记忆。
 *
 * 从 MktDetail.vue 抽出（2026-10-21）：那边加到 454 行超门禁，
 * 而这段逻辑与页面渲染无关，独立出来也更方便复用与测试。
 *
 * 【为什么用 sessionStorage 而不是 localStorage】
 * 用户要求的口径是：「勾选后这次登录不再提醒，退出登录后下次再登录还会提醒」。
 * sessionStorage 的生命周期正好是「一个标签页会话」：
 *   - 同一登录状态下切路由 / 刷新页面 → 仍记得，不重复弹
 *   - 关掉浏览器、退出后重新进入 → 自动清空，再弹一次
 * 用 localStorage 就成了永久静默，对安全告知来说太轻。
 *
 * 【退出登录的边界情况】项目退出时清的是 localStorage 的登录态，
 * sessionStorage 不会跟着清。所以这里额外提供 resetRiskAck()，
 * 由调用方在登录态消失时调用 —— 保证下次登录必弹。
 * 同时 sessionStorage 本身就随标签页销毁，多数情况无需手动清。
 */
const RISK_KEY = 'mkt_risk_ack'

export function useMktRiskNotice() {
  /** 弹窗开关 */
  const riskOpen = ref(false)

  /** 本次登录内是否已确认过。隐私模式下 sessionStorage 可能抛错 → 保守返回 false */
  function riskAcked() {
    try {
      return sessionStorage.getItem(RISK_KEY) === '1'
    } catch {
      return false
    }
  }

  /** 进入页面时决定要不要弹 */
  function maybeShowRisk() {
    if (!riskAcked()) riskOpen.value = true
  }

  /**
   * 弹窗确认回调。
   * 只在勾了「本次登录不再提醒」时才记忆；没勾就关掉，下次进入仍会弹。
   */
  function onRiskAck({ dontRemind }) {
    if (!dontRemind) return
    try {
      sessionStorage.setItem(RISK_KEY, '1')
    } catch {
      // 存不了就算了：本次仍会弹，不影响功能
    }
  }

  /** 底部那行「安全须知」链接：重新打开全文 */
  function reopenRisk() {
    riskOpen.value = true
  }

  /** 清掉记忆（退出登录时调用，保证下次登录必弹） */
  function resetRiskAck() {
    try {
      sessionStorage.removeItem(RISK_KEY)
    } catch {
      // 忽略：清不掉也只是少弹一次，不至于影响功能
    }
  }

  return { riskOpen, maybeShowRisk, onRiskAck, reopenRisk, resetRiskAck }
}

export default { useMktRiskNotice }
