import request from '@/utils/request'

export function follow(toUserId) {
  return request.post(`/relation/follow?toUserId=${toUserId}`)
}

export function unfollow(toUserId) {
  return request.delete(`/relation/follow?toUserId=${toUserId}`)
}

export function following(uid, lastId, size = 20) {
  const q = `uid=${uid}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/relation/following?${q}`)
}

export function followers(uid, lastId, size = 20) {
  const q = `uid=${uid}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/relation/followers?${q}`)
}

export function status(uid) {
  return request.get(`/relation/status?uid=${uid}`)
}

export function blacklist(uid) {
  return request.post(`/relation/blacklist?uid=${uid}`)
}

export function unblacklist(uid) {
  return request.delete(`/relation/blacklist?uid=${uid}`)
}

export function blacklistList(lastId, size = 50) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/relation/blacklist?${q}`)
}

export default { follow, unfollow, following, followers, status, blacklist, unblacklist, blacklistList }
