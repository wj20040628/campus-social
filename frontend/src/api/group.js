import request from '@/utils/request'

/** 群聊接口(镜像 api/chat.js):建群/拉人/群消息/群已读/群撤回 */

/** 我加入的群(含我侧未读) */
export function myGroups(lastId, size = 30) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/group?${q}`)
}

/** 建群 + 拉人 */
export function createGroup(name, avatarUrl, memberIds = []) {
  return request.post('/group', { name, avatarUrl, memberIds })
}

/** 群历史消息 */
export function groupMessages(groupId, lastId, size = 30) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/group/${groupId}/messages?${q}`)
}

/** 发群消息(可引用回复) */
export function sendGroupMessage(groupId, content, replyToId = null) {
  return request.post(`/group/${groupId}/message`, { content, replyToId })
}

/** 撤回群消息 */
export function recallGroupMessage(groupId, msgId) {
  return request.delete(`/group/message/${msgId}`)
}

/** 群已读 */
export function markGroupRead(groupId) {
  return request.post(`/group/${groupId}/read`)
}

export default { myGroups, createGroup, groupMessages, sendGroupMessage, recallGroupMessage, markGroupRead }
