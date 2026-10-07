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

export default { conversations, history, send, recall, markRead }
