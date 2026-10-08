import request from '@/utils/request'

/**
 * 官方公告 API(F5 / SPEC §5.3)
 *
 * 路径约定:本文件与 src/api/ 下其余模块保持一致,一律写**不含 /v1 的相对路径**
 * (如 `/announcement` 而非 `/api/v1/announcement`)。原因见 ADR-008:
 * Nginx 把对外的 `/api/v1/*` 重写为后端内部无前缀路由,
 * 前端 baseURL 已是 `/api`(.env 的 VITE_API_BASE_URL),故实际请求为 `/api/announcement`。
 * 契约字段名一律照 openapi.yaml 的 camelCase(如 isTop / topExpireTime / isRead),
 * 后端已是 camelCase 序列化,前端不做下划线转换。
 *
 * 统一响应 {code, message, data},request.js 的响应拦截器已剥掉外层,此处直接拿到 data。
 */

/* ---------------- 前台 ---------------- */

/**
 * 前台公告列表(置顶优先)
 * GET /announcement?category=&cursor=&size=
 * 返回 { list, nextCursor, hasMore }
 * @param {number|null} category 1教务2后勤 3活动 4社团 5紧急 6其他;null=全部
 * @param {number|null} cursor   游标,首页传 null
 * @param {number} size
 */
export function list({ category = null, cursor = null, size = 20 } = {}) {
  const q = [`size=${size}`]
  if (category) q.push(`category=${category}`)
  if (cursor) q.push(`cursor=${cursor}`)
  return request.get(`/announcement?${q.join('&')}`)
}

/** 未读公告数(红点)。返回 { unreadCount, latestAnnouncementId } */
export function unreadCount() {
  return request.get('/announcement/unread-count')
}

/**
 * 公告详情。返回含 isRead。
 * 注意:本接口**不写已读**,已读必须显式调 markRead(否则「谁还没看」名单失真,AC-10 失效)。
 */
export function detail(id) {
  return request.get(`/announcement/${id}`)
}

/**
 * 已读回执(幂等,AC-09)。
 * 返回 { announcementId, readTime, duplicated }
 * duplicated=true 表示此前已读过,本次未新增记录 —— 这不是错误。
 */
export function markRead(id) {
  return request.post(`/announcement/${id}/read`)
}

/* ---------------- 后台(管理员) ---------------- */

/**
 * 新建公告。publishNow=false 存草稿。
 * @returns { id, status, pushedOnlineCount }
 */
export function create({ title, content, category, isTop = 0, topExpireTime = null, publishNow = true }) {
  return request.post('/admin/announcement', {
    title,
    content,
    category,
    isTop,
    topExpireTime,
    publishNow,
  })
}

/** 后台列表(含草稿与已下架)。status: 0草稿 1已发布 2已下架;不传=全部 */
export function adminList({ status = null, cursor = null, size = 20 } = {}) {
  const q = [`size=${size}`]
  if (status !== null && status !== undefined) q.push(`status=${status}`)
  if (cursor) q.push(`cursor=${cursor}`)
  return request.get(`/admin/announcement?${q.join('&')}`)
}

/** 部分更新:置顶 / 取消置顶 / 上架 / 下架 / 改内容。isTop=1 时必须给 topExpireTime */
export function update(id, patch) {
  return request.patch(`/admin/announcement/${id}`, patch)
}

/**
 * 谁还没看(AC-10)。返回 { list, nextCursor, hasMore, totalUnread }
 * 该端点由 AC-10 反推补齐,见 openapi.yaml 的说明段。
 */
export function unreadUsers(id, { cursor = null, size = 50 } = {}) {
  const q = [`size=${size}`]
  if (cursor) q.push(`cursor=${cursor}`)
  return request.get(`/admin/announcement/${id}/unread-users?${q.join('&')}`)
}

/**
 * 一键提醒未读人群。
 * 返回 { remindedCount, totalUnread }
 * remindedCount < totalUnread 是正常的:离线用户不落库(openapi 明确说明)。
 * 60s 内重复提醒返回 429,由调用方给出「刚提醒过,稍等再试」。
 */
export function remind(id) {
  return request.post(`/admin/announcement/${id}/remind`)
}

export default {
  list,
  unreadCount,
  detail,
  markRead,
  create,
  adminList,
  update,
  unreadUsers,
  remind,
}