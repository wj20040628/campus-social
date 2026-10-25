import { ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import * as api from '@/api/announcement'

/**
 * 公告管理 —— 「谁还没看」面板(AC-10)。
 * 从 AdminAnnouncement.vue 抽出:这块有 6 个独立 state + 三种失败语义
 * (面板打开失败 / 429 限流 / 端点不支持单人入参),混在列表主流程里
 * 容易让「面板失败」污染整页的 error 态。
 */
export function useUnreadPanel() {
  const panelOpen = ref(false)
  const panelLoading = ref(false)
  const panelFailed = ref(false)
  const unreadList = ref([])
  const unreadTotal = ref(0)
  const reminding = ref(false)
  const remindedIds = ref([])
  const currentAnn = ref(null)

  function open(ann) {
    currentAnn.value = ann
    panelOpen.value = true
    // 每次打开重置:上一条公告的「已提醒」标记不能带到这一条
    remindedIds.value = []
    load()
  }

  async function load() {
    if (!currentAnn.value) return
    panelLoading.value = true
    panelFailed.value = false
    try {
      const data = await api.unreadUsers(currentAnn.value.id)
      unreadList.value = data.list || []
      // totalUnread 才是权威总数(名单只回 50 条),缺了才退回长度
      unreadTotal.value = data.totalUnread ?? unreadList.value.length
    } catch {
      panelFailed.value = true
      unreadList.value = []
      unreadTotal.value = 0
    } finally {
      panelLoading.value = false
    }
  }

  /**
   * 一键提醒:**不做二次确认**(§1.2)—— 提醒是弱打扰且是管理员高频动作。
   * 但 60s 内重复提醒会 429,必须把这个分支显式处理,
   * 否则用户看到的是「操作失败」而不是「你刚提醒过」。
   */
  async function remindAll() {
    if (!currentAnn.value || reminding.value) return
    reminding.value = true
    try {
      const data = await api.remind(currentAnn.value.id)
      ElMessage.success(`已提醒 ${data?.remindedCount ?? unreadTotal.value} 人`)
      await load()
    } catch (e) {
      ElMessage.warning(
        e?.code === 429 || e?.response?.status === 429 ? '刚提醒过,稍等再试' : '没提醒出去,再点一次',
      )
    } finally {
      reminding.value = false
    }
  }

  /**
   * 单人提醒 —— **明示不可用,不假装成功**（2026-10-24 修）。
   *
   * 原实现只往本地 remindedIds 塞标记、不发任何请求,UI 却显示「已提醒」——
   * 管理员以为提醒了那个人,实际什么都没发生。假成功比没有功能更糟:
   * 公告没触达的后果由相信了「已提醒」的管理员承担。
   *
   * 在 openapi 的 remind 端点支持按人提醒(userIds 入参)之前,这里只说真话。
   * 端点补上后,把 toast 换成一次带 userIds 的调用即可,UI 不用改。
   */
  function remindOne() {
    ElMessage.info('单人提醒暂未开放，请用右上角「一键提醒」')
  }

  return {
    panelOpen, panelLoading, panelFailed,
    unreadList, unreadTotal, reminding, remindedIds, currentAnn,
    open, load, remindAll, remindOne,
  }
}