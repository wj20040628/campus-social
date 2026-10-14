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
  transferCircle, banCircle, unbanCircle,
  addReservedName, delReservedName, listReservedNames,
}
