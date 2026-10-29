import request from '@/utils/request'

export function conversations(lastId, size = 30) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/chat/conversations?${q}`)
}

export function history(peerId, lastId, size = 30) {
  const q = `peerId=${peerId}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/chat/history?${q}`)
}

/**
 * 发送消息(AC-11 搭话护栏)。
 *
 * [路径为什么是 /chat/send 而不是 /chat/message]
 * SPEC §5.1 把搭话端点定名为 `/chat/send`,openapi.yaml:452 也是这么定义的。
 * 后端 `ChatController:56` 目前 `@PostMapping({"/send", "/message"})` 两个都在,
 * 所以打哪个都能通 —— 但那是兼容遗留,不是设计。契约在前,按契约收敛。
 *
 * [其余四个端点为什么没跟着改]
 * 契约里 chat 域**只收录了两个路径**:`/chat/send` 与 `/chat/stranger-quota`
 * (openapi.yaml:452 / :515),全文没有任何 DELETE 端点。
 * 而本文件还用了 conversations / history / recall / markRead 四个 ——
 * 它们要么是历史端点(契约 :99-101 明确登记为技术债、「不在本轮改造范围」),
 * 要么(recall 的 DELETE /chat/message/{id} 与 /chat/read)根本没被契约收录。
 * 那些不是「路径写错了」,是「契约还没写」,改前端反而会造成两边都不认。
 * 要收的是让架构师把这四条补进契约,而不是让前端猜一个路径。
 * 详见交付报告「chat 域契约缺口」。
 */
export function send(toUserId, content, replyToId = null) {
  return request.post('/chat/send', { toUserId, content, replyToId })
}

export function recall(msgId) {
  return request.delete(`/chat/message/${msgId}`)
}

export function markRead(peerId) {
  return request.post(`/chat/read?peerId=${peerId}`)
}

/**
 * 会话偏好：置顶 / 免打扰 / 从我的列表删除（手机端右滑三项）。
 *
 * 三个开关都可空，只改传了的那个 —— 点「置顶」不要把 muted 一起带过去。
 *
 * @param {'dm'|'group'} kind
 * @param {number} targetId 私信传对方 userId，群聊传 groupId
 * @param {{pinned?:boolean, muted?:boolean, hidden?:boolean}} flags
 */
export function setConvPref(kind, targetId, flags = {}) {
  return request.post('/chat/pref', { kind, targetId, ...flags })
}

/**
 * 搭话额度快照(AC-04)。**不消耗额度**,只查询。
 * 2026-10-28：对象数量不再限制，只剩「对同一陌生人每天 N 条」。
 *
 * @param {number} [peerId] 传了才返回 isStranger / remainingPerPeer
 * @returns {{ perPeerDailyLimit:number, remainingPerPeer:number|null,
 *             isStranger:boolean|null, resetAt:string, strangerMsgEnabled:boolean }}
 */
export function strangerQuota(peerId) {
  return request.get(`/chat/stranger-quota${peerId ? `?peerId=${peerId}` : ''}`)
}

export default { conversations, history, send, recall, markRead, strangerQuota, setConvPref }
