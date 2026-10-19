/**
 * 认证状态提示行(编辑资料页用,改版预览稿里的「已认证 · 友声大学」那一行)。
 *
 * [为什么单独抽出来]
 * 这段文案映射只有编辑资料页一个消费方,但它有 24 行 + 一大段注释,
 * 塞在 SettingsProfile.vue 里会把页面顶到 300 行门禁。判据与 useVerifyStatus 一致,
 * 这里只做「状态码 → 文案 + 主色」的翻译,不发请求、不改状态。
 *
 * [文案口径]
 * 已认证绿、审核中/未认证砖橙、驳回砖橙且**必须带理由** ——
 * 只说「被驳回」等于没给出口,用户会以为系统检测到了他没告诉系统的东西。
 * 与 useVerifyStatus.showRejected 的口径保持一致(有理由才说得清,没理由给重提入口)。
 */
import { computed } from 'vue'
import {
  useVerifyStatus, VERIFY_OK, VERIFY_PENDING, VERIFY_REJECTED, VERIFY_UNVERIFIED,
} from '@/composables/useVerifyStatus'

export function useVerifyTip() {
  const { code, text, rejectReason } = useVerifyStatus()

  /** @returns {{tone:'ok'|'warn', ok:boolean, text:string}|null} null = 状态未知,不渲染 */
  const tip = computed(() => {
    if (code.value === VERIFY_OK) {
      return { tone: 'ok', ok: true, text: text.value || '已认证' }
    }
    if (code.value === VERIFY_PENDING) {
      return { tone: 'warn', ok: false, text: text.value || '认证审核中,请耐心等待' }
    }
    if (code.value === VERIFY_REJECTED) {
      return {
        tone: 'warn',
        ok: false,
        text: rejectReason.value ? `认证被驳回:${rejectReason.value}` : '认证被驳回,可重新提交学号',
      }
    }
    if (code.value === VERIFY_UNVERIFIED) {
      return { tone: 'warn', ok: false, text: '未认证 · 填好学号即可提交校内认证' }
    }
    return null
  })

  return { verifyTip: tip }
}
