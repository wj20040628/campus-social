import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

/**
 * 导航项的「需登录」判定统一读路由 meta。
 *
 * 侧栏、底栏、抽屉此前各写一份锁定清单，和守卫的实际放行规则容易漂移
 * （2026-11-12 游客入口漏配就是同一类问题）。这里直接复用守卫口径：
 * requiresAuth 且未标记 guest 的路由，才是游客点了会被拦下的入口。
 */
export function useNavAccess() {
  const router = useRouter()
  const userStore = useUserStore()

  function needsLogin(path) {
    if (!userStore.isGuest) return false
    const meta = router.resolve(path).meta || {}
    return meta.requiresAuth === true && meta.guest !== true
  }

  return { needsLogin }
}
