import request from '@/utils/request'

/**
 * 校园限时活动集市（mkt）API
 * ------------------------------------------------------------------
 * 契约来源：后端 MktActivityController（@RequestMapping("/mkt")）。
 *   沟通只走站内公共留言区，不存、不外发任何对外联系方式。
 *   时间一律东八区。
 *
 * 列表均为游标分页：游标 = lastId（本页最后一条的 id），
 * 后端返回 { list, nextCursor, hasMore }，size 走既有 PageSize.clamp。
 *
 * 说明：与 circle.js / feed.js 同款手写 request 封装，
 * 不 import 第三方库、不用 TS 类型注释。
 */

/* ---------------- 创建 / 编辑 ---------------- */

/** 创建一条集市活动，返回新 id。 */
export function createMkt(dto) {
  return request.post('/mkt', dto)
}

/** 编辑：属主校验 + 敏感词；仅改文案字段，不改 expire_time。 */
export function editMkt(id, dto) {
  return request.put(`/mkt/${id}`, dto)
}

/* ---------------- 列表 ---------------- */

/** 广场：投放中、未满员、未过期、开启；可一级分类过滤。 */
export function mktPlaza(category, lastId, size = 20) {
  const q = `size=${size}${category ? `&category=${encodeURIComponent(category)}` : ''}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/mkt/plaza?${q}`)
}

/** 我的发布：带服务端推导的 4 态 status。 */
export function mktMy(lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/mkt/my?${q}`)
}

/** 详情：软删 404；过期/暂停/满员仍可看可留言。 */
export function mktDetail(id) {
  return request.get(`/mkt/${id}`)
}

/* ---------------- 属主操作 ---------------- */

export function mktEnable(id) {
  return request.post(`/mkt/${id}/enable`)
}

export function mktPause(id) {
  return request.post(`/mkt/${id}/pause`)
}

/** 手动满员标记。full=true 标记满员，false 取消。 */
export function mktFull(id, full) {
  return request.post(`/mkt/${id}/full?full=${full}`)
}

/** 重新激活：刷新到期时间 + last_launch_time；24h/3 次限流。 */
export function mktReactivate(id, newExpire) {
  return request.post(`/mkt/${id}/reactivate`, { newExpire })
}

/** 软删。 */
export function deleteMkt(id) {
  return request.delete(`/mkt/${id}`)
}

/* ---------------- 留言 ---------------- */

/** 发留言（限流 + 敏感词）；对发布者下发站内通知。 */
export function mktComment(id, content) {
  return request.post(`/mkt/${id}/comment`, { content })
}

/** 留言列表：游标分页。 */
export function mktComments(id, lastId, size = 20) {
  const q = `size=${size}${lastId ? `&lastId=${lastId}` : ''}`
  return request.get(`/mkt/${id}/comments?${q}`)
}

export default {
  createMkt, editMkt,
  mktPlaza, mktMy, mktDetail,
  mktEnable, mktPause, mktFull, mktReactivate, deleteMkt,
  mktComment, mktComments,
}
