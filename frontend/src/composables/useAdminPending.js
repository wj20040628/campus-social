/**
 * 管理端待办聚合(2026-10-24 新增,配 GET /admin/pending-summary)。
 *
 * [为什么抽出来] AppDrawer(移动端抽屉)和 SideNav(桌面侧栏)都要在
 * 管理入口挂角标,各自拉一遍既浪费也容易出现两边数字不一致。
 * 模块级缓存:首次使用拉一次,之后共享;refresh(force) 供处置动作后刷新。
 *
 * [失败语义] 角标是「锦上添花」的信息——拉不到就当没有,绝不弹 toast
 * 阻塞入口点击。管理员点进对应页面后,页面自己会拉权威数据。
 */
import { reactive, ref } from 'vue'
import { pendingSummary } from '@/api/admin'
import { useUserStore } from '@/stores/user'

/** 模块级共享状态:多个消费者(抽屉/侧栏)读同一份 */
const summary = reactive({
  reportPending: 0,
  reviewPending: 0,
  verifyPending: 0,
  feedbackPending: 0,
})
const loaded = ref(false)
/** 在途请求去重:抽屉和侧栏同帧各调一次 refresh 只发一个请求 */
let inflight = null

export function useAdminPending() {
  const userStore = useUserStore()

  /** 内容审核入口的角标 = 举报 + 机审两条队列(AdminReview 的两个 tab) */
  const reviewTotal = () => summary.reportPending + summary.reviewPending

  /** 四条队列合计。0 时管理入口整个不显示角标 */
  const total = () =>
    summary.reportPending + summary.reviewPending +
    summary.verifyPending + summary.feedbackPending

  /**
   * 拉取待办聚合。
   * @param {boolean} force true = 忽略缓存强制重拉(处置动作完成后用)
   */
  function refresh(force = false) {
    if (!userStore.isAdmin) return
    if (loaded.value && !force) return
    if (inflight) return inflight
    inflight = pendingSummary()
      .then((d) => {
        summary.reportPending = d?.reportPending ?? 0
        summary.reviewPending = d?.reviewPending ?? 0
        summary.verifyPending = d?.verifyPending ?? 0
        summary.feedbackPending = d?.feedbackPending ?? 0
        loaded.value = true
      })
      .catch(() => {
        // 静默:角标缺失只是不显示数字,不影响任何功能
      })
      .finally(() => {
        inflight = null
      })
    return inflight
  }

  return { summary, loaded, refresh, reviewTotal, total }
}
