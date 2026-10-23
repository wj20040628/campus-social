import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from '@/utils/feedback'
import {
  conversations as convApi,
  send as sendApi,
  recall as recallApi,
  markRead,
  strangerQuota,
  setConvPref,
} from '@/api/chat'
import {
  myGroups as groupsApi,
  sendGroupMessage as groupSendApi,
  recallGroupMessage as groupRecallApi,
  markGroupRead as groupMarkReadApi,
  groupMembers as groupMembersApi,
  groupMutedMe as groupMutedMeApi,
} from '@/api/group'
import { userCard } from '@/api/auth'
import { getWsClient } from '@/utils/ws'
import { useUserStore } from '@/stores/user'
import { createChatWsHandlers } from './chatWsHandlers'
import { useChatSends } from './useChatSends'
import { useChatHistory } from './useChatHistory'
import { useGroupAdminOps } from './useGroupAdminOps'

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
  /** 首屏列表加载(会话+群聊+额度三并发)是否仍在进行 */
  const initialLoading = ref(true)

  /** 群信息头:GET /group/{id}/members 的 GroupMemberInfoVO(含公告、成员名单) */
  const groupInfo = ref(null)
  /** 我是否被当前群禁言(群主恒 false);驱动 Chat.vue 的 block 提示 + 输入框禁用 */
  const mutedMe = ref(false)
  /** 群搜索:左侧群列表的本地过滤关键字(只过滤我加入的群,不发网络请求) */
  const groupSearchKw = ref('')

  /**
   * 会话列表的时间列。
   * 原来一律 MM-DD HH:mm，固定要 77px，把昵称挤到 103px ——
   * 改成相对时间后最宽只有 5 个字：今天 21:34 / 昨天 / 周三 / 10-20。
   */
  const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  const fmt = (t) => {
    if (!t) return ''
    const d = dayjs(t)
    const days = dayjs().startOf('day').diff(d.startOf('day'), 'day')
    if (days <= 0) return d.format('HH:mm')
    if (days === 1) return '昨天'
    if (days < 7) return WEEK[d.day()]
    return d.format('MM-DD')
  }
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
    // 先等列表就绪(最多 1.2s):直接进 /chat?to=X 时列表请求可能还在飞。
    // 现成会话在列表里的话,优先走 openConv —— 它带真 convId、会 markRead 清未读。
    const deadline = Date.now() + 1200
    while (!convs.value.length && !groups.value.length && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 60))
    }
    const exist = convs.value.find((c) => c.peer?.id === peerId)
    if (exist) return openConv(exist)
    // 没有现成会话:拉对方资料占位,发第一条时后端 upsert 建 conv。
    // **占位分支也必须拉历史**:历史接口按 peerId 查、与 convId 无关,
    // 这里不拉的话,从主页「发私信」进来的双方都只能看到空会话——
    // 对方明明有未读消息却显示「还没有消息」(实测:消息已落库、未读已计数,
    // 但 /chat?to=X 直进占位分支导致历史被跳过)。
    try {
      const peer = await userCard(peerId)
      currentKind.value = 'dm'
      currentGroup.value = null
      currentConv.value = { convId: 0, peer, unread: 0 }
      currentConvId.value = 0
      messages.value = []
      historyError.value = false
      markRead(peerId).catch(() => {})
      await loadHistory()
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
    // 必须 return true:openConvByPeerId 命中现成会话时 `return openConv(exist)`,
    // 调用方(Chat.vue 路由 watch)用 `if (!ok) router.replace('/chat')` 判成败。
    // 之前这里是 async 无 return → 恒 resolve undefined → 单聊(已有会话)
    // 一打开就被弹回 /chat,表现为「单聊点不开」。
    return true
  }

  async function openGroup(g) {
    currentGroup.value = g
    currentConv.value = null
    currentConvId.value = 0
    currentKind.value = 'group'
    replyToMsg.value = null
    groups.value = groups.value.map((x) => (x.id === g.id ? { ...x, myUnread: 0 } : x))
    groupMarkReadApi(g.id).catch(() => {})
    await loadGroupMembers()
    await loadMutedMe()
    await loadGroupHistory()
  }

  /** 拉群信息头(成员名单/公告/群主)。失败不阻断消息加载,head 会降级只显示群名 */
  async function loadGroupMembers() {
    if (!currentGroup.value) return
    try {
      groupInfo.value = await groupMembersApi(currentGroup.value.id)
    } catch {
      groupInfo.value = null
    }
  }

  /** 查我是否被该群禁言(非成员/单聊/查不到都置 false;群主永不被禁言,后端已保证) */
  async function loadMutedMe() {
    if (!currentGroup.value) {
      mutedMe.value = false
      return
    }
    try {
      const data = await groupMutedMeApi(currentGroup.value.id)
      mutedMe.value = !!data?.muted
    } catch {
      mutedMe.value = false
    }
  }

  /* ---------------- 历史(2026-10-15 拆去 useChatHistory.js) ---------------- */

  const { loadHistory, loadGroupHistory, batchNicknames } = useChatHistory({
    currentConv, currentGroup, messages, historyLoading, historyError, fmt, scrollBottom,
  })

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
    // 三个列表请求并发,互不依赖;任一失败不阻断其余两个。
    // 结束后关 initialLoading:此前这个状态只在 useChatCore 内部,
    // Chat.vue 的 listLoading 没人关,普通 /chat 会永远卡骨架屏。
    await Promise.allSettled([loadConvs(), loadGroups(), loadQuota()])
    initialLoading.value = false
  })

  onBeforeUnmount(() => {
    for (const [type, fn] of HANDLERS) ws.off(type, fn)
    // 不 close():其他页面(通知)可能复用同一连接
  })

  /* ---------------- 群管理(2026-10-15 拆去 useGroupAdminOps.js) ---------------- */

  const {
    onAddMembers, onKick, onRename, onSetAnnouncement, onTransfer, onLeaveGroup,
    onMuteMember, onMuteAll,
  } = useGroupAdminOps({
    currentGroup, currentKind, groupInfo, messages, groups, loadGroups, loadGroupMembers,
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

  /* ---------------- 会话偏好：置顶 / 免打扰 / 删除 ---------------- */

  /** 行数据的定位口径：私信 target=peer.id，群 target=group.id（与后端 chat_conv_pref 一致） */
  const rowTarget = (row, kind) => (kind === 'group' ? row.id : row.peer?.id)

  /**
   * 设置偏好。先改本地再落库（乐观更新），失败回滚并提示。
   *
   * 置顶靠重排体现：把这一行挪到所属分组最前，而不是新增「置顶」分区 ——
   * 分组本来就有私信/群组两层，390px 下再加一层分区，标题会连成一片。
   */
  async function setPref(row, kind, flags) {
    const targetId = rowTarget(row, kind)
    if (!targetId) return
    const listRef = kind === 'group' ? groups : convs
    const before = listRef.value.map((x) => ({ ...x }))
    const idx = listRef.value.findIndex((x) => rowTarget(x, kind) === targetId)
    if (idx < 0) return

    if ('pinned' in flags) listRef.value[idx] = { ...listRef.value[idx], pinned: flags.pinned }
    if ('muted' in flags) listRef.value[idx] = { ...listRef.value[idx], muted: flags.muted }

    if ('hidden' in flags) {
      // 删除：先从列表移除；服务端记 hidden_upto 水位，对方再发消息会自动回来
      listRef.value = listRef.value.filter((_, i) => i !== idx)
    } else if ('pinned' in flags) {
      const arr = [...listRef.value]
      const [item] = arr.splice(idx, 1)
      if (flags.pinned) arr.unshift(item)
      else arr.push(item)
      listRef.value = arr
    }

    try {
      await setConvPref(kind, targetId, flags)
    } catch (e) {
      listRef.value = before
      ElMessage.error(e?.message || '操作没生效，请重试')
    }
  }

  const pinRow = (row, kind, pinned) => setPref(row, kind, { pinned })
  const muteRow = (row, kind, muted) => setPref(row, kind, { muted })
  const deleteRow = (row, kind) => setPref(row, kind, { hidden: true })

  return {
    convs,
    groups,
    messages,
    strangerList,
    quotaLeft,
    initialLoading,
    currentKind,
    currentConv,
    currentConvId,
    currentGroup,
    groupInfo,
    mutedMe,
    groupSearchKw,
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
    onAddMembers,
    onKick,
    onRename,
    onSetAnnouncement,
    onTransfer,
    onLeaveGroup,
    onMuteMember,
    onMuteAll,
    loadMutedMe,
    pinRow,
    muteRow,
    deleteRow,
  }
}
