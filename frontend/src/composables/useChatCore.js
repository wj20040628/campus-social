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
  groupMembers as groupMembersApi,
  addGroupMembers as groupAddMembersApi,
  kickMember as groupKickApi,
  renameGroup as groupRenameApi,
  setGroupAnnouncement as groupAnnouncementApi,
  transferGroupOwner as groupTransferApi,
  leaveGroup as groupLeaveApi,
  muteMember as groupMuteApi,
  muteAllMembers as groupMuteAllApi,
  groupMutedMe as groupMutedMeApi,
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
  /** 首屏列表加载(会话+群聊+额度三并发)是否仍在进行 */
  const initialLoading = ref(true)

  /** 群信息头:GET /group/{id}/members 的 GroupMemberInfoVO(含公告、成员名单) */
  const groupInfo = ref(null)
  /** 我是否被当前群禁言(群主恒 false);驱动 Chat.vue 的 block 提示 + 输入框禁用 */
  const mutedMe = ref(false)
  /** 群搜索:左侧群列表的本地过滤关键字(只过滤我加入的群,不发网络请求) */
  const groupSearchKw = ref('')

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

  /* ---------------- 群管理(群信息头操作) ---------------- */

  /**
   * 刷新左侧群列表 + 当前群信息头。拉人/踢人/改名/公告/转让后调用。
   * 群已解散则清当前群跳列表。
   */
  async function refreshAfterGroupOp() {
    await loadGroups()
    // 若我在左侧列表里已找不到这个群(被解散/我退群),清当前群
    if (currentGroup.value && !groups.value.some((x) => x.id === currentGroup.value.id)) {
      currentGroup.value = null
      currentKind.value = 'dm'
      groupInfo.value = null
      messages.value = []
      return
    }
    await loadGroupMembers()
  }

  /** 拉人:成功后刷新名单 + 人数;满员(1045)给文案 */
  async function onAddMembers(userIds) {
    if (!currentGroup.value || !userIds?.length) return
    try {
      await groupAddMembersApi(currentGroup.value.id, userIds)
      ElMessage.success('已拉入')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1045 ? '群已满员,无法继续拉人' : '拉人失败,再试一次')
    }
  }

  /** 踢人:成功后刷新 */
  async function onKick(userId) {
    if (!currentGroup.value) return
    try {
      await groupKickApi(currentGroup.value.id, userId)
      ElMessage.success('已移出群聊')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '移出失败,再试一次')
    }
  }

  /** 改名:成功后刷新名单(群信息头带群名) */
  async function onRename(name) {
    if (!currentGroup.value || !name?.trim()) return
    try {
      await groupRenameApi(currentGroup.value.id, name.trim())
      currentGroup.value = { ...currentGroup.value, name: name.trim() }
      ElMessage.success('已改名')
      await refreshAfterGroupOp()
    } catch {
      ElMessage.error('改名失败,再试一次')
    }
  }

  /** 设公告:空=清除;成功后刷新 */
  async function onSetAnnouncement(text) {
    if (!currentGroup.value) return
    try {
      await groupAnnouncementApi(currentGroup.value.id, text)
      await loadGroupMembers()
      ElMessage.success(text?.trim() ? '公告已更新' : '公告已清除')
    } catch {
      ElMessage.error('设置公告失败,再试一次')
    }
  }

  /** 转让:成功后我降为普通成员,刷新(名单里 isOwner 标自动变) */
  async function onTransfer(newOwnerId) {
    if (!currentGroup.value) return
    try {
      await groupTransferApi(currentGroup.value.id, newOwnerId)
      ElMessage.success('群主已转让')
      await refreshAfterGroupOp()
    } catch (e) {
      ElMessage.error(e?.code === 1026 ? '对方不是该群成员' : '转让失败,再试一次')
    }
  }

  /** 退群:非群主直接退;群主退则自动转让或解散。跳列表 */
  async function onLeaveGroup() {
    if (!currentGroup.value) return
    const gid = currentGroup.value.id
    try {
      await groupLeaveApi(gid)
      ElMessage.success('已退出群聊')
      await loadGroups()
      currentGroup.value = null
      currentKind.value = 'dm'
      groupInfo.value = null
      messages.value = []
    } catch (e) {
      ElMessage.error(e?.code === 1026 ? '你不是该群成员' : '退出群聊失败,再试一次')
    }
  }

  /* ---------------- 群禁言(群主) ---------------- */

  /** 禁言某成员:成功后刷新名单(muted 标随之变) */
  async function onMuteMember(userId, muted = true) {
    if (!currentGroup.value) return
    try {
      await groupMuteApi(currentGroup.value.id, userId, muted)
      ElMessage.success(muted ? '已禁言' : '已解除禁言')
      await loadGroupMembers()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '操作失败,再试一次')
    }
  }

  /** 禁言全员:成功后刷新名单(全员 muted 标随之变) */
  async function onMuteAll(muted = true) {
    if (!currentGroup.value) return
    try {
      await groupMuteAllApi(currentGroup.value.id, muted)
      ElMessage.success(muted ? '已全员禁言' : '已解除全员禁言')
      await loadGroupMembers()
    } catch (e) {
      ElMessage.error(e?.code === 1027 ? '仅群主可操作' : '操作失败,再试一次')
    }
  }

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
  }
}
