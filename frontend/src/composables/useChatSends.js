import { ElMessage } from '@/utils/feedback'

/**
 * 消息的「发送 / 撤回」两条写路径 —— 从 useChatCore.js 抽出。
 * 共同点是它们都要**在内存里先乐观插入一条气泡**,失败时不能回滚输入内容:
 * 用户打完 50 字点发送,失败后一片空白,只能重新打一遍 —— 这是最伤的交互。
 *
 * @param {object} ctx
 * @param {import('vue').Ref} ctx.currentGroup
 * @param {import('vue').Ref} ctx.currentConv
 * @param {import('vue').Ref} ctx.currentKind    'dm' | 'group'
 * @param {import('vue').Ref} ctx.messages
 * @param {import('vue').Ref} ctx.replyToMsg
 * @param {Function} ctx.fmt             (iso) => 'MM-DD HH:mm'
 * @param {Function} ctx.scrollBottom
 * @param {Function} ctx.loadConvs
 * @param {Function} ctx.loadGroups
 * @param {Function} ctx.loadHistory
 * @param {Function} ctx.loadGroupHistory
 * @param {number}   ctx.myId
 * @param {{ send: Function, groupSend: Function }} ctx.api
 */
export function useChatSends(ctx) {
  const {
    currentGroup, currentConv, currentKind, messages, replyToMsg,
    fmt, scrollBottom, loadConvs, loadGroups, loadHistory, loadGroupHistory,
    myId, api,
  } = ctx

  /**
   * @param {import('vue').Ref<string>} draft 引用而不是字符串 —— 失败时调用方不清它
   * @returns {{ ok: boolean, id: number|null }} 失败时 ok=false,UI 层追加「未发出」气泡
   */
  async function doSend(draft) {
    const content = draft.value.trim()
    if (!content) return { ok: false, id: null }
    if (!currentGroup.value && !currentConv.value) return { ok: false, id: null }

    const replyId = replyToMsg.value?.id || null
    const replyContent = replyToMsg.value?.content || null
    // 引用块的署名:自己的引用显示「我」,否则用被引用消息的发送者
    const replyName = replyToMsg.value?.fromMe
      ? '我'
      : replyToMsg.value?.senderNickname || currentConv.value?.peer?.nickname || '对方'

    try {
      let msgId
      if (currentKind.value === 'group') {
        const res = await api.groupSend(currentGroup.value.id, content, replyId)
        msgId = res?.msgId || Date.now()
        messages.value.push(baseBubble(msgId, content, replyId, replyContent, replyName, true))
        loadGroups().catch(() => {})
      } else {
        const res = await api.send(currentConv.value.peer?.id, content, replyId)
        msgId = res?.msgId || Date.now()
        messages.value.push(baseBubble(msgId, content, replyId, replyContent, replyName, false))
        loadConvs().catch(() => {})
      }
      replyToMsg.value = null
      scrollBottom()
      return { ok: true, id: msgId }
    } catch {
      // 草稿由调用方保留 —— 这里不清 draft.value
      return { ok: false, id: null }
    }
  }

  /** 单聊气泡与群消息共用同一份形状,只有 senderId/senderNickname 不同 */
  function baseBubble(msgId, content, replyId, replyContent, replyName, isGroup) {
    return {
      id: msgId,
      content,
      fromMe: true,
      // 群消息要带 senderNickname 让 UI 对齐左侧;单聊不需要(有 peer)
      ...(isGroup ? { senderId: myId } : {}),
      createTime: fmt(new Date().toISOString()),
      sendTs: Date.now(),
      // status:0 = 对方还没读。不能用 1,否则「已读」会假性全亮
      status: 0,
      replyToId: replyId,
      replyToContent: replyId ? replyContent || '(已撤回)' : null,
      replyToNickname: replyId ? replyName : null,
    }
  }

  /** 8s 内、非撤回、我发的消息才可撤。服务端同样判窗口,前端判只是为了不给出无效按钮 */
  const canRecall = (m) =>
    m.fromMe && !m.recalled && m.sendTs && Date.now() - m.sendTs < 8000

  async function doRecall(m) {
    if (m._recallBusy) return
    m._recallBusy = true
    try {
      if (currentKind.value === 'group') await api.groupRecall(m.id)
      else await api.recall(m.id)
      // DB 是真相源:重拉一次,后端已把 content 置空 + recalled=true。
      // 前端本地改只能改自己收到的那条,漏推的历史消息会显示正文 —— 那更糟。
      if (currentKind.value === 'group') await loadGroupHistory()
      else await loadHistory()
      ElMessage.success('已撤回')
    } catch {
      ElMessage.error('撤回了,消息还在')
    } finally {
      // 窗口 8s:1s 后解锁,防「点一次没反应再点」
      setTimeout(() => { m._recallBusy = false }, 1000)
    }
  }

  return { doSend, canRecall, doRecall }
}