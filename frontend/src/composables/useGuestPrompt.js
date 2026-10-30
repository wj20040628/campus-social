import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'

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
   * @returns {Promise<boolean>} true=已登录可直接继续;false=游客(已弹框,本次动作终止)
   */
  async function ensureLogin(text = '登录后才能继续，现在去登录吗？') {
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
    router.push({ path: '/login/form', query: { returnUrl: route.fullPath } })
    return false
  }

  return { ensureLogin }
}
