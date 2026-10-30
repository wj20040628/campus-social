/**
 * 校园信息状态提示行(编辑资料页用)。
 *
 * 2026-10-30 起这只是可选资料的状态，不代表任何权限：
 * 已登记只说明资料与字典一致，不对外宣称「已认证身份」。
 *
 * [文案口径]
 * 已登记绿、核对中/未填写砖橙、需修改砖橙且**必须带理由** ——
 * 只说「需修改」等于没给出口。
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
      return { tone: 'ok', ok: true, text: text.value || '校园信息已登记' }
    }
    if (code.value === VERIFY_PENDING) {
      return { tone: 'warn', ok: false, text: text.value || '校园信息核对中,请耐心等待' }
    }
    if (code.value === VERIFY_REJECTED) {
      return {
        tone: 'warn',
        ok: false,
        text: rejectReason.value
          ? `校园信息需修改:${rejectReason.value}`
          : '校园信息需修改,可重新提交学号',
      }
    }
    if (code.value === VERIFY_UNVERIFIED) {
      return { tone: 'warn', ok: false, text: '校园信息未填写（选填，不影响使用）' }
    }
    return null
  })

  return { verifyTip: tip }
}
