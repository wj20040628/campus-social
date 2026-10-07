import request from '@/utils/request'

export function list(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/notify?${q}`)
}

export function read(id) {
  return request.post(`/notify/read?id=${id}`)
}

export function readAll() {
  return request.post('/notify/read-all')
}

export function unread() {
  return request.get('/notify/unread')
}

export default { list, read, readAll, unread }
