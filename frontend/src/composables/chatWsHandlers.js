/**
 * 聊天的 WebSocket 事件处理 —— 从 useChatCore.js 抽出。
 *
 * 为什么单独成文件:6 个 WS 事件各有「是不是当前会话」的分支,内联在 core 里
 * 会把主数据流(列表 / 打开 / 历史 / 发送)挤到看不清。且这段逻辑的注册与解绑
 * 必须成对出现,HANDLERS 数组是唯一真理源。
 *
 * 关键约定:
 * - **任何事件都要刷列表**:非当前会话的来消息要冒红点,不能只在当前会话才刷
 * - 当前会话收到消息即视为已读,立刻回一个 markRead,否则红点消不掉
 * - 群消息的 senderNickname 要额外查,单聊不用(peer 已经带昵称)
 */

/**
 * @param {object} ctx
 * @param {import('vue').Ref} ctx.currentConv     当前单聊会话
 * @param {import('vue').Ref} ctx.currentConvId
 * @param {import('vue').Ref} ctx.currentGroup    当前群
 * @param {import('vue').Ref} ctx.currentKind     'dm' | 'group'
 * @param {import('vue').Ref} ctx.messages
 * @param {import('vue').Ref} ctx.convs
 * @param {import('vue').Ref} ctx.groups
 * @param {Function} ctx.markRead           (peerId) => Promise
 * @param {Function} ctx.batchNicknames     (ids) => Promise<Map>
 * @param {Function} ctx.fmt                (iso) => 'MM-DD HH:mm'
 * @param {Function} ctx.scrollBottom
 * @param {Function} ctx.loadConvs          () => Promise
 * @param {Function} ctx.loadGroups         () => Promise
 * @param {Function} ctx.groupMarkRead      (groupId) => Promise
 * @returns {Array<[string, Function]>} HANDLERS,注册与解绑共用同一份
 */
export function createChatWsHandlers(ctx) {
  const {
    currentConv, currentConvId, currentGroup, currentKind,
    messages, convs, groups,
    markRead, batchNicknames, fmt, scrollBottom,
    loadConvs, loadGroups, groupMarkRead,
  } = ctx

  function onChat(payload) {
    const isCurrent = !!currentConv.value && !!payload.convId && currentConvId.value === payload.convId
    if (isCurrent) {
      messages.value.push({
        id: payload.msgId,
        content: payload.content,
        fromMe: false,
        senderId: payload.senderId,
        createTime: fmt(new Date().toISOString()),
        sendTs: Date.now(),
        // 我正开着这个会话,来消息即视为已读
        status: 1,
      })
      scrollBottom()
      convs.value = convs.value.map((x) =>
        x.convId === payload.convId ? { ...x, unread: 0 } : x,
      )
      if (currentConv.value?.peer?.id) {
        markRead(currentConv.value.peer.id).catch(() => {})
      }
    }
    // 无论如何刷列表:非当前会话的要冒红点
    loadConvs().catch(() => {})
  }

  function onChatRecalled(payload) {
    if (currentConvId.value !== payload.convId) return
    messages.value = messages.value.map((m) =>
      m.id === payload.msgId ? { ...m, recalled: true, content: '', replyToContent: null } : m,
    )
  }

  function onChatRead(payload) {
    if (payload.readerId !== currentConv.value?.peer?.id) return
    messages.value = messages.value.map((m) => (m.fromMe ? { ...m, status: 1 } : m))
  }

  async function onGroupMsg(payload) {
    const isCurrent = currentKind.value === 'group' && currentGroup.value?.id === payload.groupId
    if (isCurrent) {
      // 群消息没有 peer 带昵称,得单独查一次(群里 50 条可能有 20 个 sender)
      const nickMap = await batchNicknames([payload.senderId])
      messages.value.push({
        id: payload.msgId,
        content: payload.content,
        fromMe: false,
        senderId: payload.senderId,
        senderNickname: nickMap.get(payload.senderId),
        createTime: fmt(new Date().toISOString()),
        sendTs: Date.now(),
      })
      scrollBottom()
      groups.value = groups.value.map((x) =>
        x.id === payload.groupId ? { ...x, myUnread: 0 } : x,
      )
      groupMarkRead(payload.groupId).catch(() => {})
    }
    loadGroups().catch(() => {})
  }

  function onGroupRecalled(payload) {
    if (currentKind.value !== 'group' || currentGroup.value?.id !== payload.groupId) return
    messages.value = messages.value.map((m) =>
      m.id === payload.msgId ? { ...m, recalled: true, content: '', replyToContent: null } : m,
    )
  }

  function onGroupRead(payload) {
    if (currentKind.value !== 'group' || currentGroup.value?.id !== payload.groupId) return
    messages.value = messages.value.map((m) => (m.fromMe ? { ...m, status: 1 } : m))
  }

  return [
    ['chat', onChat],
    ['chat_read', onChatRead],
    ['chat_recalled', onChatRecalled],
    ['group_msg', onGroupMsg],
    ['group_recalled', onGroupRecalled],
    ['group_read', onGroupRead],
  ]
}