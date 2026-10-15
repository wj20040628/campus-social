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
import { ElMessage } from '@/utils/feedback'

export function useChatComposer(core) {
  const { messages, doSend } = core

  const draft = ref('')
  const sending = ref(false)
  /** 会话组件实例:滚动到底由它提供(桌面/单栏共用一个 ref) */
  const threadRef = ref(null)

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
      ElMessage.error('没发出去,点气泡重试')
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
      ElMessage.error('还是没发出去,检查一下网络')
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
