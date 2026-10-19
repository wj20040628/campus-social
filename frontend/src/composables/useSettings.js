/**
 * 设置页(Settings.vue)的管理后台弹层与退出登录(2026-10-15 从 Settings.vue 拆出)。
 * ------------------------------------------------------------------
 * 拆出的理由:Settings.vue 471 行超过 300 行门禁。这里收拢的是
 * 「页面级开关 + 一次请求」这类逻辑。
 *
 * 2026-10-18 精简:粉丝/关注名单弹层与「我的审核记录」弹层已从本 composable 移除 ——
 * 粉丝/关注本就是个人主页(Profile.vue)的 Tab 内容,审核记录也挪到了
 * Profile.vue 概览区的独立入口(计划 §3)。本 composable 现在只负责:
 *   1. 管理后台弹层(仅 isAdmin)
 *   2. 退出登录(与 MainLayout 顶栏同一语义)
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'

export function useSettings() {
  const userStore = useUserStore()
  const router = useRouter()

  /* ---- 管理后台弹层 ---- */
  const adminSheetOpen = ref(false)

  function goAdmin(path) {
    adminSheetOpen.value = false
    router.push(path).catch(() => {})
  }

  /* ---- 退出登录 ---- */
  /**
   * 与 MainLayout 顶栏的退出是同一语义:确认 -> 吊销 -> 清本地 -> 回登录页。
   * 后端调不通(网络断)时也必须清本地跳走 —— 不能把人困在「已退出但 token
   * 还在」的状态里,那会导致退不出又登不进。
   */
  async function doLogout() {
    try {
      await ElMessageBox.confirm('确定退出登录吗?', '退出登录', {
        confirmButtonText: '退出',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      await userStore.logout()
    } catch {
      userStore.clear()
    }
    ElMessage.success('已退出登录')
    router.replace('/login/form')
  }

  return {
    adminSheetOpen, goAdmin,
    doLogout,
  }
}
