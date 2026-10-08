import request from '@/utils/request'

export function conversations(lastId, size = 30) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/chat/conversations?${q}`)
}

export function history(peerId, lastId, size = 30) {
  const q = `peerId=${peerId}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/chat/history?${q}`)
}

export function send(toUserId, content, replyToId = null) {
  return request.post('/chat/message', { toUserId, content, replyToId })
}

export function recall(msgId) {
  return request.delete(`/chat/message/${msgId}`)
}

export function markRead(peerId) {
  return request.post(`/chat/read?peerId=${peerId}`)
}

/**
 * 今日剩余破冰额度(AC-04)。**不消耗额度**,只查询。
 * @param {number} [peerId] 传了才返回 remainingPerPeer(对特定对象的剩余条数)
 * @returns {{ dailyNewPeerLimit:number, dailyNewPeerUsed:number,
 *             remainingNewPeer:number, perPeerDailyLimit:number,
 *             remainingPerPeer:number|null }}
 */
export function strangerQuota(peerId) {
  return request.get(`/chat/stranger-quota${peerId ? `?peerId=${peerId}` : ''}`)
}

export default { conversations, history, send, recall, markRead, strangerQuota }
