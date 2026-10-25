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
      /*
       * 气泡文本用服务端返回的 content(2026-10-24):发送路径入库前会替换
       * 一级敏感词(MessageContentGuard),本地草稿是原文——不换的话,
       * 发送者自己看到原文、接收方看到星号,同一句话两种内容。
       */
      if (currentKind.value === 'group') {
        const res = await api.groupSend(currentGroup.value.id, content, replyId)
        msgId = res?.msgId || Date.now()
        messages.value.push(baseBubble(msgId, res?.content || content, replyId, replyContent, replyName, true))
        loadGroups().catch(() => {})
      } else {
        const res = await api.send(currentConv.value.peer?.id, content, replyId)
        msgId = res?.msgId || Date.now()
        messages.value.push(baseBubble(msgId, res?.content || content, replyId, replyContent, replyName, false))
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

  /** 120s 内、非撤回、我发的、**有有效 msgId**的消息才可撤。
   *  sendTs 来自服务器 createTime(历史消息)或本地发送时刻(刚发出),前端只用来
   *  不给出注定无效的按钮,真正的拦截在服务端。
   *  id 守卫:刚发出时若后端响应异常、id 落到 undefined(或前端生成的占位),
   *  这里不渲染撤回按钮,从根上避免点撤回触发 400「参数类型错误: msgId」。 */
  const canRecall = (m) =>
    m != null && m.id != null &&
    m.fromMe && !m.recalled && m.sendTs && Date.now() - m.sendTs < 120000

  async function doRecall(m) {
    if (m._recallBusy) return
    const content = m.content ?? ''
    // 终极兜底:msgId 必须是有限的数字才发请求。undefined/NaN/前端时间戳占位
    // 都打回去提示,绝不让它落到 URL 变成 .../message/undefined 触发 400。
    let msgId = Number(m.id)
    // 二次保险:调用方(旧 HMR 闭包 / WS 推送的脏数据)可能丢了 id —— 从本地
    // messages 数组按「内容 + fromMe + 未撤回 + 120s 窗口」重新锁定那条气泡,
    // 取它的 id。日志已证明这条路径拿到的 id 就是后端返回的数字。
    if (!Number.isFinite(msgId) || msgId <= 0) {
      const match = (messages.value || []).find((x) =>
        x.fromMe && !x.recalled &&
        x.sendTs && Date.now() - x.sendTs < 120000 &&
        x.content === content && Number(x.id) > 0,
      )
      if (match) msgId = Number(match.id)
    }
    if (!Number.isFinite(msgId) || msgId <= 0) {
      ElMessage.error('这条消息没拿到有效 id,刷新后再试')
      return
    }
    m._recallBusy = true
    try {
      if (currentKind.value === 'group') {
        // recallGroupMessage(groupId, msgId):两个参数,groupId 在前。漏掉会导致 msgId=undefined。
        await api.groupRecall(currentGroup.value.id, msgId)
      } else {
        await api.recall(msgId)
      }
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