import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from '@/utils/feedback'
import {
  conversations as convApi,
  history as historyApi,
  send as sendApi,
  recall as recallApi,
  markRead,
  strangerQuota,
} from '@/api/chat'
import {
  myGroups as groupsApi,
  groupMessages as groupMsgApi,
  sendGroupMessage as groupSendApi,
  recallGroupMessage as groupRecallApi,
  markGroupRead as groupMarkReadApi,
} from '@/api/group'
import { userCard } from '@/api/auth'
import { getWsClient } from '@/utils/ws'
import { useUserStore } from '@/stores/user'
import { createChatWsHandlers } from './chatWsHandlers'
import { useChatSends } from './useChatSends'

/**
 * 会话状态与实时收发(Chat.vue.md §7)
 *
 * 从 Chat.vue 抽出来的原因:原文件 1072 行里,数据/WS 与模板样式混在一起,
 * 改一处 WS 分支要在大段 CSS 里找对应 class。拆开后:
 *   - 本文件 = 数据与实时(约 300 行)
 *   - ChatConvList.vue = 列表 UI
 *   - ChatThread.vue    = 消息窗 UI
 *   - Chat.vue          = 路由分支与编排
 *
 * ## 单一状态源
 *
 * `currentKind`('dm' | 'group')决定读哪套 API。原实现靠
 * `if (currentKind === 'group')` 在每个函数里分叉,共 6 处。
 * 这里保留同样的结构而不是抽象成 adapter —— 6 处分叉的 adapter
 * 会引入一层「假装统一」的间接,群消息的 senderNickname 聚合、
 * 已读语义(群=至少一人读到)都与单聊不同,强行统一反而更容易错。
 *
 * @param {{ onNeedScrollBottom: () => void }} hooks
 */
export function useChatCore({ onNeedScrollBottom } = {}) {
  const userStore = useUserStore()
  const ws = getWsClient()

  const convs = ref([])
  const groups = ref([])
  const messages = ref([])
  const currentKind = ref('dm')
  const currentConv = ref(null)
  const currentConvId = ref(null)
  const currentGroup = ref(null)
  const replyToMsg = ref(null)

  /** 历史加载失败(与「没有消息」区分:前者要给出重试出口) */
  const historyError = ref(false)
  const historyLoading = ref(false)
  /** 陌生人消息:待回应的搭话纸条(AC-01) */
  const strangerList = ref([])
  /** 破冰额度:今天还能主动打招呼几次(AC-04) */
  const quotaLeft = ref(null)

  const fmt = (t) => (t ? dayjs(t).format('MM-DD HH:mm') : '')
  const scrollBottom = () => onNeedScrollBottom?.()

  /* ---------------- 列表 ---------------- */

  async function loadConvs() {
    const data = await convApi(null, 50)
    convs.value = (data?.list || []).map((c) => ({ ...c, timeText: fmt(c.lastMsgTime) }))
  }

  async function loadGroups() {
    const data = await groupsApi(null, 50)
    groups.value = (data?.list || []).map((g) => ({ ...g, timeText: fmt(g.lastMsgTime) }))
  }

  /**
   * 破冰额度(AC-04)。查询**不消耗额度**,所以可以随手调。
   * 拉取失败静默保持 null,上游据此**不渲染额度条** ——
   * 宁可没有,也不要给一个恒为 4 的假额度(用户按 4 行事结果第 5 次被拒,
   * 会认定是 bug 而不是额度用完)。
   */
  async function loadQuota() {
    try {
      const data = await strangerQuota()
      quotaLeft.value = data?.remainingNewPeer ?? null
    } catch {
      quotaLeft.value = null
    }
  }

  /* ---------------- 打开会话 ---------------- */

  async function openConvByPeerId(peerId) {
    const exist = convs.value.find((c) => c.peer?.id === peerId)
    if (exist) return openConv(exist)
    // 没有现成会话:拉对方资料占位,发第一条时后端 upsert 建 conv
    try {
      const peer = await userCard(peerId)
      currentKind.value = 'dm'
      currentGroup.value = null
      currentConv.value = { convId: 0, peer, unread: 0 }
      currentConvId.value = 0
      messages.value = []
      historyError.value = false
    } catch {
      ElMessage.error('打开会话失败,回列表重试')
      return false
    }
    return true
  }

  async function openConv(c) {
    currentConv.value = c
    currentConvId.value = c.convId
    currentKind.value = 'dm'
    // 切会话时清引用条:防 A→B→C 之后引用条上还挂着 B 的内容
    replyToMsg.value = null
    convs.value = convs.value.map((x) => (x.convId === c.convId ? { ...x, unread: 0 } : x))
    if (c.peer?.id) markRead(c.peer.id).catch(() => {})
    await loadHistory()
  }

  async function openGroup(g) {
    currentGroup.value = g
    currentConv.value = null
    currentConvId.value = 0
    currentKind.value = 'group'
    replyToMsg.value = null
    groups.value = groups.value.map((x) => (x.id === g.id ? { ...x, myUnread: 0 } : x))
    groupMarkReadApi(g.id).catch(() => {})
    await loadGroupHistory()
  }

  /* ---------------- 历史 ---------------- */

  async function loadHistory() {
    if (!currentConv.value) return
    historyLoading.value = true
    historyError.value = false
    try {
      const data = await historyApi(currentConv.value.peer?.id, null, 50)
      messages.value = (data?.list || []).map((m) => ({
        ...m,
        createTime: fmt(m.createTime),
        sendTs: m.createTime ? new Date(m.createTime).getTime() : 0,
      }))
      scrollBottom()
    } catch {
      // 保留已有消息,只在「一条都没有」时整区报错
      historyError.value = true
    } finally {
      historyLoading.value = false
    }
  }

  async function loadGroupHistory() {
    if (!currentGroup.value) return
    historyLoading.value = true
    historyError.value = false
    try {
      const data = await groupMsgApi(currentGroup.value.id, null, 50)
      const sids = [...new Set((data?.list || []).map((m) => m.senderId).filter(Boolean))]
      const nickMap = await batchNicknames(sids)
      messages.value = (data.list || []).map((m) => ({
        ...m,
        createTime: fmt(m.createTime),
        sendTs: m.createTime ? new Date(m.createTime).getTime() : 0,
        senderNickname: nickMap.get(m.senderId),
      }))
      scrollBottom()
    } catch {
      historyError.value = true
    } finally {
      historyLoading.value = false
    }
  }

  /** 批量查昵称。逐个 await 是串行的,这里显式并发 —— 群消息 50 条可能有 20 个不同 sender */
  const nickCache = new Map()
  async function batchNicknames(ids) {
    const out = new Map()
    const todo = [...new Set(ids)].filter((id) => id && !nickCache.has(id))
    await Promise.all(
      todo.map(async (id) => {
        try {
          const u = await userCard(id)
          nickCache.set(id, u.nickname || u.username)
        } catch {
          // 单个查不到不影响整屏:降级为「用户<id>」而不是整块报错
          nickCache.set(id, `用户${id}`)
        }
      }),
    )
    ids.forEach((id) => out.set(id, nickCache.get(id)))
    return out
  }

  /* ---------------- 发送 / 撤回 ---------------- */

  const { doSend, canRecall, doRecall } = useChatSends({
    currentGroup, currentConv, currentKind, messages, replyToMsg,
    fmt, scrollBottom, loadConvs, loadGroups, loadHistory, loadGroupHistory,
    myId: userStore.userInfo?.id,
    api: { send: sendApi, groupSend: groupSendApi, recall: recallApi, groupRecall: groupRecallApi },
  })

  /* ---------------- WS ---------------- */

  // 6 个事件的处理体在 chatWsHandlers.js;这里只负责注册 / 解绑
  const HANDLERS = createChatWsHandlers({
    currentConv, currentConvId, currentGroup, currentKind,
    messages, convs, groups,
    markRead, batchNicknames, fmt, scrollBottom,
    loadConvs, loadGroups, groupMarkRead: groupMarkReadApi,
  })

  onMounted(async () => {
    for (const [type, fn] of HANDLERS) ws.on(type, fn)
    ws.connect('/ws/chat')
    // 三个列表请求并发,互不依赖;任一失败不阻断其余两个
    await Promise.allSettled([loadConvs(), loadGroups(), loadQuota()])
  })

  onBeforeUnmount(() => {
    for (const [type, fn] of HANDLERS) ws.off(type, fn)
    // 不 close():其他页面(通知)可能复用同一连接
  })

  /* ---------------- 派生 ---------------- */

  const headTitle = computed(() => {
    if (currentKind.value === 'group') return currentGroup.value?.name || '群聊'
    return currentConv.value?.peer?.nickname || currentConv.value?.peer?.username || '会话'
  })

  const headSub = computed(() =>
    currentKind.value === 'group' ? `${currentGroup.value?.memberCount || 0} 人` : '',
  )

  /** 桌面双栏下「当前是否选中了一个会话」。/chat 无参数时为 false,渲染右侧空态 */
  const hasActive = computed(() =>
    currentKind.value === 'group' ? !!currentGroup.value : !!currentConv.value,
  )

  return {
    convs,
    groups,
    messages,
    strangerList,
    quotaLeft,
    currentKind,
    currentConv,
    currentConvId,
    currentGroup,
    replyToMsg,
    historyError,
    historyLoading,
    headTitle,
    headSub,
    hasActive,
    canRecall,
    loadConvs,
    loadGroups,
    openConv,
    openGroup,
    openConvByPeerId,
    loadHistory,
    doSend,
    doRecall,
  }
}
