import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'

/*
 * 登录后动作回放:游客在点赞/收藏的确认框里选了「去登录」,
 * 登录成功回跳后自动把原动作执行一次，不用再点。
 * 只存内存不落盘 —— 刷新页面后不回放，避免执行一个用户早已忘记的动作。
 */
const PENDING_TTL = 5 * 60 * 1000
let pending = null

/**
 * 游客点了需要登录的操作:当场弹一次说明,愿意登录再带 returnUrl 跳转。
 * 不做常驻横幅 —— 提示只出现在用户真正动手的那一刻。
 */
export function useGuestPrompt() {
  const router = useRouter()
  const route = useRoute()
  const userStore = useUserStore()

  /**
   * @param {string} text 按动作给具体的一句话
   * @param {Function|null} action 登录成功后要补做的动作(可选)
   * @param {string|null} returnUrl 登录后落点(可选,默认回当前页)
   * @returns {Promise<boolean>} true=已登录可直接继续;false=游客(已弹框,本次动作终止)
   */
  async function ensureLogin(text = '登录后才能继续，现在去登录吗？', action = null, returnUrl = null) {
    if (userStore.hasToken) return true
    try {
      await ElMessageBox.confirm(text, '登录后才能继续', {
        type: 'info',
        confirmButtonText: '去登录',
        cancelButtonText: '先逛逛',
      })
    } catch {
      return false
    }
    if (typeof action === 'function') pending = { action, at: Date.now() }
    router.push({ path: '/login/form', query: { returnUrl: returnUrl || route.fullPath } })
    return false
  }

  return { ensureLogin }
}

/** 登录成功后调用。失败不阻断登录跳转:动作自己已负责提示 */
export async function runPendingGuestAction() {
  const item = pending
  pending = null
  if (!item || Date.now() - item.at > PENDING_TTL) return
  try {
    await item.action()
  } catch {
    /* 动作内部已有错误提示,这里只保证登录流程继续 */
  }
}
