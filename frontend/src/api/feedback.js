import request from '@/utils/request'

/**
 * 意见反馈 API(2026-10-18,导航抽屉「意见反馈」)
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml
 *   POST /feedback
 *   GET  /admin/feedback
 *   POST /admin/feedback/{id}/handle
 *
 * ## 为什么不复用 submitReport
 * 举报是对具体对象的违规行为,有 targetType/targetId 且幂等(同一目标只能一条);
 * 反馈是对产品本身的建议,没有举报对象、允许一个人反复提。
 * 把反馈塞进 /report 会被 uk_report 的幂等约束误伤 ——
 * 用户第二次反馈会收到「已记录」,而他明明在提一个全新的问题。
 */

/** 反馈分类。与后端 FeedbackSubmitDTO.category 同码,别在前端另编一套 */
export const FEEDBACK_CATEGORIES = [
  { value: 1, label: '功能建议', hint: '希望友声增加什么' },
  { value: 2, label: '使用问题', hint: '哪里点不动、报错' },
  { value: 3, label: '内容问题', hint: '看到不合适的内容' },
  { value: 4, label: '界面体验', hint: '排版、配色、操作别扭' },
  { value: 5, label: '其他', hint: '说不清归属哪类' },
]

/** category 值 -> 文案。管理端列表直接把数字翻成人话,不写 switch */
export const CATEGORY_TEXT = Object.fromEntries(
  FEEDBACK_CATEGORIES.map((c) => [c.value, c.label]),
)

/** status 值 -> 文案。与后端 0待处理 1已采纳 2已忽略 一致 */
export const FEEDBACK_STATUS_TEXT = {
  0: '待处理',
  1: '已采纳',
  2: '已忽略',
}

/**
 * 提交反馈。提交即进入管理员审核队列(status 恒为 0)。
 *
 * **不幂等**,所以响应里没有 duplicated:同一个问题提两次就是两条记录,
 * 这正是我们要的 —— 用户提两次说明他很在意。
 *
 * @param {{category:1|2|3|4|5, content:string, media?:Array<{type:'img'|'video',url:string}>}} body
 *        media 由 useMediaUpload 的 readyMedia 直接给(字符串 type),转换在本层做
 * @returns {Promise<{feedbackId:number, status:0}>}
 */
export function submitFeedback(body) {
  const payload = {
    category: body.category,
    content: body.content,
  }
  // 有附件才带 media:后端 media 为 null 与空数组等价,但少一个字段少一处校验
  if (body.media && body.media.length) payload.media = toWireMedia(body.media)
  return request.post('/feedback', payload)
}

/**
 * readyMedia 的 `type` 是 'img'/'video' 字符串(发帖落库格式),
 * 而反馈契约的 MediaItem.type 是 1/2 整数 —— 后端 Jackson 遇到 "img" 会抛
 * HttpMessageNotReadableException,表现为「服务器内部错误」而不是 400。
 * 转换收在这一层:DTO 保持类型严格(数字类型才能挡住脏值),
 * 组件侧继续用与发帖同形的字符串,两边的约定各自只有一个真源。
 */
function toWireMedia(media) {
  return media.map((m) => ({
    type: m.type === 'video' ? 2 : 1,
    url: m.url,
  }))
}

/**
 * 反馈列表(管理员,游标分页)。
 *
 * 游标参数名是 `cursor` + 响应 `nextCursor`(与 /admin/verify 同款),
 * **不是** 机审队列那套 `lastId` —— 名字传错后端拿到 null 会静默返回第一页,
 * 于是「上拉加载更多」反复追加同一页 20 条。
 *
 * `pendingCount` 是 status=0 的全量总数,红点用。
 *
 * @param {{status?:0|1|2, cursor?:number|null, size?:number}} [params]
 * @returns {Promise<{list:Array, nextCursor:number|null, hasMore:boolean, pendingCount:number}>}
 */
export function adminFeedback(params = {}) {
  const { status, cursor, size = 20 } = params
  const query = { size }
  if (status !== undefined && status !== null) query.status = status
  if (cursor !== undefined && cursor !== null) query.cursor = cursor
  return request.get('/admin/feedback', { params: query })
}

/**
 * 处置反馈:adopt 采纳 / ignore 忽略,handleNote 必填 1~200 字。
 *
 * 重复处置回 409(乐观锁 WHERE status=0)。所以 suppressStatuses 挡 409:
 * 「已经被别人处理了」是正常竞争结果,不该让管理员看到一条红色报错,
 * 由调用方翻成人话并刷新列表。
 *
 * @param {number} id
 * @param {{action:'adopt'|'ignore', handleNote:string}} body
 */
export function handleFeedback(id, body) {
  return request.post(`/admin/feedback/${id}/handle`, body, { suppressStatuses: [409] })
}

/**
 * 我的反馈列表(当前用户自己提交的,游标分页)。
 * 与 adminFeedback 同款游标参数(cursor / nextCursor)。
 *
 * @param {{cursor?:number|null, size?:number}} [params]
 * @returns {Promise<{list:Array, nextCursor:number|null, hasMore:boolean}>}
 */
export function myFeedback(params = {}) {
  const { cursor, size = 20 } = params
  const query = { size }
  if (cursor !== undefined && cursor !== null) query.cursor = cursor
  return request.get('/feedback/mine', { params: query })
}

export default {
  FEEDBACK_CATEGORIES,
  CATEGORY_TEXT,
  FEEDBACK_STATUS_TEXT,
  submitFeedback,
  adminFeedback,
  handleFeedback,
  myFeedback,
}
