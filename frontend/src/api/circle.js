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

export function circlePosts(id, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/circle/${id}/posts?${q}`)
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

export function signup(id) {
  return request.post(`/activity/${id}/signup`)
}

export function cancelSignup(id) {
  return request.post(`/activity/${id}/cancel`)
}

export function search(q, type = 'all', size = 20) {
  return request.get(`/search?q=${encodeURIComponent(q)}&type=${type}&size=${size}`)
}

export default {
  createCircle, list, detail, join, leave, circlePosts,
  createActivity, activitiesByCircle, activityDetail, signup, cancelSignup,
  search,
}
