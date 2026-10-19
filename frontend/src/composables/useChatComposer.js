/**
 * 会话页的输入 / 发送 / 重试(2026-10-15 从 Chat.vue 拆出)。
 * ------------------------------------------------------------------
 * 底层 doSend 在 useChatSends(经 useChatCore 暴露);这里只管 UI 侧:
 * 草稿状态、发送中标记、失败气泡、重试,以及它们与滚动的配合。
 *
 * 失败语义原样搬迁:
 *   - 发送失败**保留草稿**:清空输入框是最伤的交互
 *   - 失败时追加一条 failed 气泡:界面毫无变化会让人以为按钮坏了而狂点
 *   - 重试先撤掉失败气泡:成功时不能留下两条一样的消息
 */
import { ref, nextTick } from 'vue'

export function useChatComposer(core) {
  const { messages, doSend } = core

  const draft = ref('')
  const sending = ref(false)
  /** 会话组件实例:滚动到底由它提供(桌面/单栏共用一个 ref) */
  const threadRef = ref(null)

  /**
   * 追加一条「未发出」气泡并滚到底。
   * 界面毫无变化会让人以为按钮坏了而狂点,失败气泡就是这一下反馈。
   *
   * 这里**刻意不弹 toast**:失败原因已由 request.js 全局拦截器给出
   * (业务错误弹后端 message,如「对方已关闭陌生人消息,无法发送」;
   * 网络错误弹「网络错误」)。再叠一条笼统的「没发出去,点气泡重试」
   * 会变成同一次失败弹两条互相打架的提示 —— 用户要的是原因,不是重试口号;
   * 重试入口由气泡自身(「未发出 / 没发出去,点这里重试」)承载。
   */
  async function addFailedBubble(text) {
    messages.value.push({
      id: `local-${Date.now()}`,
      content: text,
      fromMe: true,
      createTime: nowText(),
      sendTs: Date.now(),
      status: 0,
      failed: true,
    })
    await nextTick()
    threadRef.value?.scrollBottom()
  }

  async function onSend() {
    if (sending.value || !draft.value.trim()) return
    sending.value = true
    const text = draft.value
    try {
      const res = await doSend(draft)
      if (res.ok) {
        draft.value = ''
        return
      }
      await addFailedBubble(text)
    } finally {
      sending.value = false
    }
  }

  async function retrySend(m) {
    const text = m.content
    // 先撤掉失败气泡:重试成功时页面不能留下两条一样的消息
    messages.value = messages.value.filter((x) => x.id !== m.id)
    draft.value = text
    const res = await doSend(draft)
    if (res.ok) {
      draft.value = ''
    } else {
      draft.value = text
      // 再失败:挂回失败气泡,保留「点气泡重试」入口(失败原因见全局 toast)
      await addFailedBubble(text)
    }
  }

  /** 本地失败气泡的时间戳:与 feed 的 MM-DD HH:mm 口径一致,不走服务端 */
  function nowText() {
    const d = new Date()
    const p = (n) => String(n).padStart(2, '0')
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
  }

  return { draft, sending, threadRef, onSend, retrySend }
}
