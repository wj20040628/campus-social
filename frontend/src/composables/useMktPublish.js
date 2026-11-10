import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

/**
 * 集市「发布」入口的统一守卫：只拦游客。
 * 发布频率、信誉分门槛与在投配额由后端判定；前端不复制阈值，避免后端调参后前端仍按旧值拦人。
 */
export function useMktPublish() {
  const router = useRouter()
  const userStore = useUserStore()
  const { ensureLogin } = useGuestPrompt()

  async function goPublish() {
    if (!userStore.hasToken) {
      await ensureLogin('登录后才能发布集市活动，现在去登录吗？')
      return
    }
    router.push('/mkt/publish')
  }

  return { goPublish }
}
