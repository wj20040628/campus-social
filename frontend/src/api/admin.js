import request from '@/utils/request'

/**
 * 圈子官方名保护 + 管理员处置(仅 role=1 管理员)。
 * 端点挂载在 /admin/circles/**,类级 @RequireAdmin,非管理员 403 NOT_ADMIN。
 */

// ---------------- 圈子处置 ----------------

/** 移交圈主:把圈主位交给圈内某成员(原圈主降为普通成员)。 */
export function transferCircle(id, newOwnerId) {
  return request.post(`/admin/circles/${id}/transfer`, { newOwnerId })
}

/** 封禁圈子(status=1)。 */
export function banCircle(id) {
  return request.post(`/admin/circles/${id}/ban`)
}

/** 解封圈子。 */
export function unbanCircle(id) {
  return request.post(`/admin/circles/${id}/unban`)
}

/** 恢复解散的圈子(思路 B 兜底:圈 status 1→0 + 圈内帖子 status 2→1,仅管理员)。 */
export function restoreCircle(id) {
  return request.post(`/admin/circles/${id}/restore`)
}

/** 全量圈子列表(含 status=1 封禁/解散),按 id 倒序。管理员处置用。 */
export function listAllCircles(lastId, size = 100) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/admin/circles?${q}`)
}

// ---------------- 预留官方名 ----------------

/** 预留某圈名(建圈撞名直接拦)。 */
export function addReservedName(name, note) {
  return request.post('/admin/circles/reserved-names', { name, note })
}

/** 移除预留名。 */
export function delReservedName(name) {
  return request.delete(`/admin/circles/reserved-names/${encodeURIComponent(name)}`)
}

/** 预留名列表(游标分页)。 */
export function listReservedNames(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/admin/circles/reserved-names?${q}`)
}

export default {
  transferCircle, banCircle, unbanCircle, restoreCircle, listAllCircles,
  addReservedName, delReservedName, listReservedNames,
  adminUsers, banUser, unbanUser, pendingSummary,
}

// ---------------- 用户处置(2026-10-24 新增) ----------------
// 此前封人只能走举报流程、解封没有任何端点(永久封禁误判后只能改库)。
// 这组接口补齐:找人 → 直接封禁 / 手动解封。

/**
 * 用户处置列表。
 * @param {{keyword?: string, status?: 0|1, cursor?: number|null, size?: number}} [params]
 *   keyword:昵称/登录名/学号模糊匹配;status: 0 正常 / 1 冻结(封禁中),不传 = 全部
 */
export function adminUsers(params = {}) {
  const { keyword, status, cursor, size = 20 } = params
  const query = { size }
  if (keyword) query.keyword = keyword
  if (status !== undefined && status !== null) query.status = status
  if (cursor) query.cursor = cursor
  return request.get('/admin/users', { params: query })
}

/** 直接封禁(不依赖举报流程)。banDays 0=永久;reason 必填,写 audit_log。 */
export function banUser(userId, banDays, reason) {
  return request.post(`/admin/users/${userId}/ban`, { banDays, reason })
}

/** 手动解封。此前唯一的解封路径是到期定时任务,永久封禁不可撤销——本端点是救济路径。 */
export function unbanUser(userId) {
  return request.post(`/admin/users/${userId}/unban`)
}

/**
 * 四条队列待办聚合(管理入口角标数据源)。
 * 举报 / 机审 / 校园信息核对 / 反馈,一次拉全,不必逐页巡检。
 */
export function pendingSummary() {
  return request.get('/admin/pending-summary')
}
