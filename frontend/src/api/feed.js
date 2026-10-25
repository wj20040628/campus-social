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
  // opts: { by, order, topic, anonymous } 排序/筛选;anonymous=true 只看树洞
  const params = [`size=${size}`]
  if (lastId) params.push(`lastId=${lastId}`)
  if (opts.by) params.push(`by=${opts.by}`)
  if (opts.order) params.push(`order=${opts.order}`)
  if (opts.topic) params.push(`topic=${encodeURIComponent(opts.topic)}`)
  if (opts.anonymous) params.push('anonymous=true')
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

/** 收藏(幂等)。后端不会给作者发通知,收藏是私人整理动作 */
export function favorite(id) {
  return request.post(`/post/${id}/favorite`)
}

/** 取消收藏(幂等)。后端走物理删除,不会留下占唯一键的残留行 */
export function unfavorite(id) {
  return request.delete(`/post/${id}/favorite`)
}

/**
 * 我收藏的帖子(按收藏时间倒序)。
 *
 * 注意 lastId 是**收藏记录 id**,不是帖子 id —— 排序依据是收藏时间,
 * 用帖子 id 做游标会让"刚收藏一条旧帖"翻页错乱。上一页的 nextCursor 原样回传即可。
 */
export function favoriteList(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/favorites?${q}`)
}

/** 我赞过的帖子(按点赞时间倒序),游标语义同 favoriteList */
export function likedList(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/post/liked?${q}`)
}

export function comment(postId, dto) {
  return request.post(`/post/${postId}/comment`, dto)
}

/** 删除自己的评论(连同其子回复)。403 删他人的;404 COMMENT_NOT_FOUND 已删。 */
export function deleteComment(postId, commentId) {
  return request.delete(`/post/${postId}/comment/${commentId}`)
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

/** 某用户发的动态(他人主页「动态」tab 用)。按 author_id 现查,非登录人绑死 */
export function userPosts(userId, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/user/${userId}/posts?${q}`)
}

export function upload(file, kind = 'image') {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kind', kind)
  return request.post('/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    /*
     * per-request 超时(2026-10-23 加,审查 P1-35):
     * 全局默认 30s 对 25MB 视频必然超时(4G 上传通常要 1-2 分钟),
     * 兜底上传链路会莫名失败且提示"timeout of 30000ms exceeded"。
     * OSS 直传早就放宽到 120s,服务端兜底不能停留在 30s。
     */
    timeout: 120000,
  })
}

/**
 * 能力探测(无副作用):当前上传配置。
 * 前端据此判断视频功能开没开(videoEnabled)+ 单个视频/图片能传多大。
 * @returns {Promise<{videoEnabled:boolean, maxVideoBytes:number, maxImageBytes:number}>}
 */
export function uploadConfig() {
  return request.get('/upload/config')
}

export default {
  createPost, detail, deletePost, feed, feedFollowing, topicFeed, hot,
  like, unlike, comment, comments, commentChildren, myPosts, userPosts, upload,
  uploadConfig,
}
