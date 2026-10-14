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

// ---- 群管理(群信息头 + 群主/成员操作) ----

/** 查群信息头:群名/人数/群主/公告 + 成员名单 */
export function groupMembers(groupId) {
  return request.get(`/group/${groupId}/members`)
}

/** 拉人(仅群主):把一批已存在、未入群的成员加进现有群 */
export function addGroupMembers(groupId, userIds) {
  return request.post(`/group/${groupId}/members`, { userIds })
}

/** 踢人(仅群主):把某普通成员移出 */
export function kickMember(groupId, userId) {
  return request.post(`/group/${groupId}/kick`, { userId })
}

/** 群改名(仅群主) */
export function renameGroup(groupId, name) {
  return request.put(`/group/${groupId}/name`, { name })
}

/** 群公告(仅群主),空=清除 */
export function setGroupAnnouncement(groupId, text) {
  return request.put(`/group/${groupId}/announcement`, { text })
}

/** 群主转让(仅群主,指定对象):目标须是群成员且非我 */
export function transferGroupOwner(groupId, newOwnerId) {
  return request.post(`/group/${groupId}/transfer`, { newOwnerId })
}

/** 退群:非群主直接退;群主退则自动转让或解散 */
export function leaveGroup(groupId) {
  return request.delete(`/group/${groupId}/members/me`)
}

// ---- 群禁言(群主) ----

/** 禁言某成员(仅群主):muted=true 禁言 / false 解除,缺省 true */
export function muteMember(groupId, userId, muted = true) {
  return request.post(`/group/${groupId}/mute`, { userId, muted })
}

/** 禁言全员(仅群主):muted=true 全员禁言 / false 解除全员,缺省 true */
export function muteAllMembers(groupId, muted = true) {
  return request.post(`/group/${groupId}/mute-all`, { muted })
}

/** 我是否被该群禁言(前端据此禁用输入框) */
export function groupMutedMe(groupId) {
  return request.get(`/group/${groupId}/muted-me`)
}

export default {
  myGroups, createGroup, groupMessages, sendGroupMessage, recallGroupMessage, markGroupRead,
  groupMembers, addGroupMembers, kickMember, renameGroup, setGroupAnnouncement,
  transferGroupOwner, leaveGroup,
  muteMember, muteAllMembers, groupMutedMe,
}
