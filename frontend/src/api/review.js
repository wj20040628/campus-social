import request from '@/utils/request'

export function pending(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/review/pending?${q}`)
}

export function processed(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/review/processed?${q}`)
}

export function approve(id) {
  return request.post(`/review/${id}/approve`)
}

export function reject(id, reason) {
  return request.post(`/review/${id}/reject`, { reason })
}

// 作者侧:查自己帖子的审核结果(被驳理由/命中词/AI 预判)
export function mine(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/review/mine?${q}`)
}

export default { pending, processed, approve, reject, mine }
