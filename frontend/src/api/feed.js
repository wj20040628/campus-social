import request from '@/utils/request'

export function createPost(dto) {
  return request.post('/post', dto)
}

export function updatePost(id, dto) {
  return request.put(`/post/${id}`, dto)
}

export function detail(id) {
  return request.get(`/post/${id}`)
}

export function deletePost(id) {
  return request.delete(`/post/${id}`)
}

export function feed(lastId, size = 20, opts = {}) {
  // opts: { by, order, topic } 排序/筛选
  const params = [`size=${size}`]
  if (lastId) params.push(`lastId=${lastId}`)
  if (opts.by) params.push(`by=${opts.by}`)
  if (opts.order) params.push(`order=${opts.order}`)
  if (opts.topic) params.push(`topic=${encodeURIComponent(opts.topic)}`)
  return request.get(`/post/feed?${params.join('&')}`)
}

export function feedFollowing(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/feed/following?${q}`)
}

export function topicFeed(name, lastId, size = 20) {
  const q = `topic=${encodeURIComponent(name)}&size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/topic?${q}`)
}

export function hot(size = 20) {
  return request.get(`/post/hot?size=${size}`)
}

export function like(id) {
  return request.post(`/post/${id}/like`)
}

export function unlike(id) {
  return request.delete(`/post/${id}/like`)
}

export function comment(postId, dto) {
  return request.post(`/post/${postId}/comment`, dto)
}

export function comments(postId, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/${postId}/comments?${q}`)
}

/**
 * 某条评论的全部直接子回复(溢出补齐用)。
 *
 * 契约 `GET /post/{id}/comment/{commentId}/children`:
 * - **不递归**,只查直接子回复(二级→三级、三级无子,一层就够)
 * - `nextCursor` 按子回复自身 id 游标,与一级列表口径一致
 * - `data.totalForParent` 是该节点的直接子回复**总数**(不受本次 size 限制),
 *   用它算「共 N 条,已加载 M 条」—— 不要把 childrenOverflowCount 和
 *   本页长度反复相加,两处相加漏一次就会显示错总数
 */
export function commentChildren(postId, commentId, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/${postId}/comment/${commentId}/children?${q}`)
}

export function myPosts(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/mine?${q}`)
}

export function upload(file, kind = 'image') {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kind', kind)
  return request.post('/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

export default {
  createPost, detail, deletePost, feed, feedFollowing, topicFeed, hot,
  like, unlike, comment, comments, commentChildren, myPosts, upload,
}
