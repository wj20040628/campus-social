import request from '@/utils/request'

export function createCircle(name) {
  return request.post('/circle', { name })
}

export function list(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/circle?${q}`)
}

export function detail(id) {
  return request.get(`/circle/${id}`)
}

export function join(id) {
  return request.post(`/circle/${id}/join`)
}

export function leave(id) {
  return request.post(`/circle/${id}/leave`)
}

/** 解散圈子(仅圈主 role=1):软删圈子 + 级联软删圈内帖子。圈管理/成员 403。 */
export function disband(id) {
  return request.post(`/circle/${id}/disband`)
}

export function circlePosts(id, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/circle/${id}/posts?${q}`)
}

// 圈管理 / 吧主级权限(需 role<=2 圈内成员,或圈主)
export function topPost(id, postId) {
  return request.post(`/circle/${id}/post/${postId}/top`)
}

export function untopPost(id, postId) {
  return request.delete(`/circle/${id}/post/${postId}/top`)
}

export function takedownPost(id, postId) {
  return request.post(`/circle/${id}/post/${postId}/takedown`)
}

export function movePost(id, postId, toCircleId) {
  return request.post(`/circle/${id}/post/${postId}/move`, { toCircleId })
}

export function appointAdmin(id, userId) {
  return request.post(`/circle/${id}/member/${userId}/admin`)
}

export function revokeAdmin(id, userId) {
  return request.delete(`/circle/${id}/member/${userId}/admin`)
}

export function banMember(id, userId, until) {
  return request.post(`/circle/${id}/member/${userId}/ban`, until ? { until } : {})
}

export function unbanMember(id, userId) {
  return request.post(`/circle/${id}/member/${userId}/unban`)
}

export function circleMembers(id) {
  return request.get(`/circle/${id}/members`)
}

// 活动
export function createActivity(dto) {
  return request.post('/activity', dto)
}

export function activitiesByCircle(circleId, lastId, size = 20) {
  const q = `circleId=${circleId}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/activity/circle?${q}`)
}

export function activityDetail(id) {
  return request.get(`/activity/${id}`)
}

/**
 * 活动报名者名单（2026-10-21 新增）。
 *
 * 公开可读，后端只返回昵称 + 头像 + isMe。
 * 与活动详情拆成两个请求而不是塞进 ActivityVO：
 * 名单是「可能变、可能很长」的数据，详情是「一次拿全」，
 * 拆开后详情页可以先渲染主体、名单后到，不会因为名单慢而整页等。
 */
export function activitySignups(id) {
  return request.get(`/activity/${id}/signups`)
}

export function signup(id) {
  return request.post(`/activity/${id}/signup`)
}

export function cancelSignup(id) {
  return request.post(`/activity/${id}/cancel`)
}

export function closeActivity(id) {
  return request.post(`/activity/${id}/close`)
}

export function search(q, type = 'all', size = 20) {
  return request.get(`/search?q=${encodeURIComponent(q)}&type=${type}&size=${size}`)
}

export default {
  createCircle, list, detail, join, leave, disband, circlePosts,
  topPost, untopPost, takedownPost, movePost,
  appointAdmin, revokeAdmin, banMember, unbanMember, circleMembers,
  createActivity, activitiesByCircle, activityDetail, signup, cancelSignup,
  closeActivity,
  search,
}
