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
  adminUsers, banUser, unbanUser, muteUser, unmuteUser, pendingSummary,
}

// ---------------- 用户处置(2026-10-24 新增) ----------------
// 此前封人只能走举报流程、解封没有任何端点(永久封禁误判后只能改库)。
// 这组接口补齐:找人 → 直接封禁 / 手动解封。

/**
 * 用户处置列表。
 * @param {{keyword?: string, status?: 0|1|2, muted?: boolean, cursor?: number|null,
 *          sort?: 'created'|'recentPunish', size?: number}} [params]
 *   keyword:昵称/登录名/学号模糊匹配;status: 0 正常 / 1 冻结(封禁中) / 2 已注销;
 *   muted: true = 只看禁言中(mute_until 未到期);都不传 = 全部(已注销除外);
 *   sort: created=注册时间（默认）; recentPunish=最近封禁/禁言
 */
export function adminUsers(params = {}) {
  const { keyword, status, muted, cursor, sort, size = 20 } = params
  const query = { size }
  if (keyword) query.keyword = keyword
  if (status !== undefined && status !== null) query.status = status
  if (muted) query.muted = true
  if (cursor) query.cursor = cursor
  if (sort && sort !== 'created') query.sort = sort
  return request.get('/admin/users', { params: query })
}

/** 直接封禁(不依赖举报流程)。banDays 0=永久;reason 必填,写 audit_log。 */
export function banUser(userId, banDays, reason) {
  return request.post(`/admin/users/${userId}/ban`, { banDays, reason })
}

/** 直接禁言(不依赖举报流程)。muteDays 1~365;reason 必填,同步告知被处置人。 */
export function muteUser(userId, muteDays, reason) {
  return request.post(`/admin/users/${userId}/mute`, { muteDays, reason })
}

/** 手动解封。此前唯一的解封路径是到期定时任务,永久封禁不可撤销——本端点是救济路径。 */
export function unbanUser(userId) {
  return request.post(`/admin/users/${userId}/unban`)
}

/** 手动解除禁言。禁言会自动到期，误判/申诉成立时可提前恢复。 */
export function unmuteUser(userId) {
  return request.post(`/admin/users/${userId}/unmute`)
}

/**
 * 四条队列待办聚合(管理入口角标数据源)。
 * 举报 / 机审 / 校园信息核对 / 反馈,一次拉全,不必逐页巡检。
 */
export function pendingSummary() {
  return request.get('/admin/pending-summary')
}

/**
 * 管理总览(2026-11-07 新增)：待办 + 今日新增 + 站内规模 + 风控，一次拉全。
 * 今日口径是自然日 00:00 起算，不是滚动 24 小时。
 */
export function adminOverview() {
  return request.get('/admin/overview')
}

/**
 * 用户处置详情（2026-11-08）：基础资料 + 最近举报 + 封禁/禁言轨迹 + 最近帖子。
 * 用于管理员在封人前判断「初犯还是惯犯」，不用再逐个点主页翻。
 */
export function adminUserDetail(userId) {
  return request.get(`/admin/users/${userId}/detail`)
}

/** AI 机审配置自检：是否启用、缺哪个环境变量。不含密钥。 */
export function adminReviewAiStatus() {
  return request.get('/admin/review/ai-status')
}
