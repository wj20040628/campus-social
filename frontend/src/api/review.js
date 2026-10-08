import request from '@/utils/request'

/**
 * 审核 / 举报处置 API
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml
 *   /review/pending  /review/processed  /review/{id}/approve  /review/{id}/reject
 *   /review/mine    /report
 *   /admin/reports   /admin/reports/{id}/handle
 *
 * 举报端点是 F4 上线门槛项:AC-14 要求「管理员在 375px 视口能完成举报处置」,
 * 但 SPEC §5.1 把端点写在 G1 段落下 —— 不接就等于 AC-14 无解。
 */

/* ---------------- 内容审核(机审队列) ---------------- */

/** 待审队列。游标分页,cursor 传 null 取第一页 */
export function pending(lastId, size = 20) {
  return request.get('/review/pending', { params: { lastId, size } })
}

/** 已处理队列。同 pending 的游标语义 */
export function processed(lastId, size = 20) {
  return request.get('/review/processed', { params: { lastId, size } })
}

/** 审核通过 */
export function approve(id) {
  return request.post(`/review/${id}/approve`)
}

/** 审核驳回。reason 为空时后端按默认理由落库 */
export function reject(id, reason) {
  return request.post(`/review/${id}/reject`, null, { params: { reason } })
}

/** 我提交过的审核历史(普通用户可见) */
export function mine(lastId, size = 20) {
  return request.get('/review/mine', { params: { lastId, size } })
}

/* ---------------- 举报(用户侧) ---------------- */

/**
 * 提交举报。
 *
 * 契约 openapi §ReportSubmitRequest:
 *   targetType 1用户 2帖子 3评论 4聊天消息 5搭子
 *   reasonType 1骚扰 2广告 3色情 4诈骗 5其他
 *
 * 幂等(openapi §6):重复举报同一 targetType+targetId 返回 200 +
 * `duplicated = true` + message「已记录」,**不是 4xx**。
 * 所以调用方不能靠抛异常判重,要读 data.duplicated ——
 * 否则用户第二次举报会看到红色报错,以为没生效接着点。
 *
 * `reasonText` 请求侧上限 200(openapi §ReportSubmitRequest),
 * 展示侧后端截到 60 并回 reasonTextTruncated —— 前端不必自己截到 60。
 *
 * @param {{targetType:1|2|3|4|5, targetId:number, reasonType:1|2|3|4|5, reasonText?:string}} body
 * @returns {Promise<{reportId:number, duplicated:boolean, status:0|1|2}>}
 */
export function submitReport(body) {
  const payload = {
    targetType: body.targetType,
    targetId: body.targetId,
    reasonType: body.reasonType,
  }
  // 只在有内容时带 reasonText:空串会被后端 maxLength 校验白跑一趟,
  // 而且空串在审核台里显示成一条空白备注,管理员分辨不出是「没填」还是「漏了」
  if (body.reasonText) payload.reasonText = body.reasonText
  return request.post('/report', payload)
}

/* ---------------- 举报处置(管理员) ---------------- */

/**
 * 举报列表。
 *
 * `status` 不传表示全部;前端只传 0/1/2 三个显式值,不传 undefined ——
 * axios 会把 undefined 的参数从 query 里剔掉,但传空串会变成 `?status=`,
 * 后端按「空字符串」解析会 400。宁可少传一个可选参数,不多传一个空值。
 *
 * @param {{status?: 0|1|2, lastId?: number|null, size?: number}} [params]
 */
export function adminReports(params = {}) {
  const { status, lastId, size = 20 } = params
  const query = { size }
  if (status !== undefined && status !== null) query.status = status
  if (lastId !== undefined && lastId !== null) query.lastId = lastId
  return request.get('/admin/reports', { params: query })
}

/**
 * 处置举报。
 *
 * action 三选一:
 *   ban    成立并封禁目标用户,banDays 必填(1/7/30,0 表示永久)
 *   reject 举报不成立,记录置 status=2
 *   ignore 忽略,不改 status,仅记录处置人与备注
 *
 * 同一举报重复处置返 409 CONFLICT —— 调用方需要能区分「已经被人处置了」
 * 和「网络失败」,前者不该让管理员重试。
 *
 * @param {number} id
 * @param {{action:'ban'|'reject'|'ignore', banDays?:0|1|7|30, handleNote?:string}} body
 */
export function handleReport(id, body) {
  return request.post(`/admin/reports/${id}/handle`, body)
}

export default {
  pending,
  processed,
  approve,
  reject,
  mine,
  submitReport,
  adminReports,
  handleReport,
}
