/**
 * 「我的」页的三个弹层与退出登录(2026-10-15 从 Settings.vue 拆出)。
 * ------------------------------------------------------------------
 * 拆出的理由:Settings.vue 471 行超过 300 行门禁。这里收拢的是
 * 「弹层状态 + 打开逻辑」这一整类:粉丝/关注名单、管理后台入口、
 * 我的审核记录、退出登录 —— 四件事的共同点是「页面级开关 + 一次请求」,
 * 和概览卡/帖子列表的渲染无关。退出登录留在这里而不是各页自己写一份:
 * MainLayout 顶栏也有一個退出入口,两边行为必须一致(见 doLogout 注释)。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { mine as myReviewApi } from '@/api/review'
import { following as followingApi, followers as followersApi } from '@/api/relation'
import { useUserStore } from '@/stores/user'

export function useSettings() {
  const userStore = useUserStore()
  const router = useRouter()

  /* ---- 粉丝 / 关注名单弹层 ---- */
  const listSheetOpen = ref(false)
  const listSheetKind = ref('followers')
  const listSheetList = ref([])
  const listSheetLoading = ref(false)
  const relLoading = ref({ followers: false, following: false })

  /** 计数封顶:超过 9999 显示 9999+ */
  function cap(n) {
    return n > 9999 ? '9999+' : n
  }

  async function openList(kind) {
    listSheetKind.value = kind
    listSheetOpen.value = true
    listSheetLoading.value = true
    listSheetList.value = []
    const meId = userStore.userInfo?.id
    try {
      const data = kind === 'followers'
        ? await followersApi(meId, null, 50)
        : await followingApi(meId, null, 50)
      listSheetList.value = (data?.list || []).filter(Boolean)
    } finally {
      listSheetLoading.value = false
      relLoading.value[kind] = false
    }
  }

  /* ---- 管理后台弹层 ---- */
  const adminSheetOpen = ref(false)

  function goAdmin(path) {
    adminSheetOpen.value = false
    router.push(path).catch(() => {})
  }

  /* ---- 我的审核记录弹层 ---- */
  const reviewSheetOpen = ref(false)
  const reviewList = ref([])
  const reviewLoading = ref(false)

  async function openReview() {
    reviewSheetOpen.value = true
    if (reviewList.value.length) return // 已拉过就不重复请求
    reviewLoading.value = true
    try {
      // 契约同 useProfileData.normalize:直接取 {list}
      const data = await myReviewApi(null, 50)
      reviewList.value = (data?.list || []).filter(Boolean)
    } catch {
      reviewList.value = []
    } finally {
      reviewLoading.value = false
    }
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
    listSheetOpen, listSheetKind, listSheetList, listSheetLoading, relLoading, cap, openList,
    adminSheetOpen, goAdmin,
    reviewSheetOpen, reviewList, reviewLoading, openReview,
    doLogout,
  }
}
