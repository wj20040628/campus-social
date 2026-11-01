/**
 * 会话历史加载(2026-10-15 从 useChatCore.js 拆出)。
 * ------------------------------------------------------------------
 * 收拢三件事:单聊历史、群聊历史、批量昵称解析。
 *
 * 群历史要额外聚合 senderNickname(单聊不需要),且接口与已读语义
 * (群=至少一人读到)都不同 —— 这是两者不合并成一个函数的原因,
 * 见 useChatCore.js 头注「不抽象成 adapter」的说明。
 *
 * 失败语义原样保留:历史拉失败**保留已有消息**,只在一条都没有时
 * 才整区报错给重试出口。
 */
import { userCard } from '@/api/auth'
import { history as historyApi } from '@/api/chat'
import { groupMessages as groupMsgApi } from '@/api/group'

export function useChatHistory({
  currentConv, currentGroup, messages, historyLoading, historyError, fmt, scrollBottom,
}) {
  /** 昵称缓存:群消息 50 条可能有 20 个不同 sender,不缓存会反复查同一批人 */
  const nickCache = new Map()

  /** 批量查昵称。逐个 await 是串行的,这里显式并发 */
  async function batchNicknames(ids) {
    const out = new Map()
    const todo = [...new Set(ids)].filter((id) => id && !nickCache.has(id))
    await Promise.all(
      todo.map(async (id) => {
        try {
          // 群历史里可能有已注销成员发的旧消息；/user/{id} 对 status=2 返回 404，
          // 不能让它走全局 toast（否则每次进群都弹「用户不存在」）。
          const u = await userCard(id, { silent: true })
          nickCache.set(id, u.nickname || u.username)
        } catch (e) {
          // 单个查不到不影响整屏:404 显示已注销,其他错误降级为「用户<id>」
          // HTTP 404 走 Axios 错误分支,码在 response.data.code;只有 HTTP 200
          // 的业务错误才带 businessCode。少读一路会把已注销降级成「用户<id>」。
          const code = e?.businessCode ?? e?.response?.data?.code
          nickCache.set(id, code === 404 ? '已注销用户' : `用户${id}`)
        }
      }),
    )
    ids.forEach((id) => out.set(id, nickCache.get(id)))
    return out
  }

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

  return { loadHistory, loadGroupHistory, batchNicknames }
}
