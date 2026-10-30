import request from '@/utils/request'

/**
 * 审核 / 举报处置 API
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml
 *   /review/pending  /review/processed  /review/{id}/approve  /review/{id}/reject
 *   /review/mine    /report
 *   /admin/reports   /admin/reports/{id}/handle
 *   /admin/verify/pending  /admin/verify/{userId}/approve  /admin/verify/{userId}/reject
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
 *   targetType 1用户 2帖子 3评论 4私聊消息 5搭子 6集市活动 7文件 8群消息 9圈子
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
 * @param {{targetType:1|2|3|4|5|6|7, targetId:number, reasonType:1|2|3|4|5, reasonText?:string}} body
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
 * 【为什么用 handled 而不是只靠 status 区分两个列表】
 * 「忽略」按契约不改 status（仍是 0），只写 handler_id。于是单看 status：
 *   - 待处理列表过滤不掉已忽略的（status 仍是 0，刷新后又冒出来）
 *   - 已处理列表也表达不出它（status 不是 1/2）
 * handler_id 才是三种处置（忽略/驳回/成立封禁）都会落下的可靠标记。
 *
 * @param {{handled?: boolean, status?: 0|1|2, lastId?: number|null, size?: number}} [params]
 *   handled=false → 待处理（status=0 且无人处置过）
 *   handled=true  → 已处理（含被忽略的）
 *   不传 handled  → 退回按 status 过滤（兼容旧调用）
 */
export function adminReports(params = {}) {
  const { status, handled, lastId, size = 20 } = params
  const query = { size }
  if (handled !== undefined && handled !== null) {
    query.handled = handled
  } else if (status !== undefined && status !== null) {
    // handled 已能表达全部语义，两者同时传反而让人猜不透以谁为准
    query.status = status
  }
  if (lastId !== undefined && lastId !== null) query.lastId = lastId
  return request.get('/admin/reports', { params: query })
}

/**
 * 处置举报。
 *
 * action 四选一:
 *   ban      成立并封禁目标用户（同时下架被举报内容）,banDays 必填(1/7/30,0 表示永久)
 *   takedown 成立、仅下架内容，不封人（2026-10-28 新增）
 *   reject   举报不成立,记录置 status=2
 *   ignore   忽略,不改 status,仅记录处置人与备注
 *
 * 同一举报重复处置返 409 CONFLICT —— 调用方需要能区分「已经被人处置了」
 * 和「网络失败」,前者不该让管理员重试。
 *
 * @param {number} id
 * @param {{action:'ban'|'takedown'|'reject'|'ignore', banDays?:0|1|7|30, handleNote?:string}} body
 */
export function handleReport(id, body) {
  return request.post(`/admin/reports/${id}/handle`, body)
}

/* ---------------- 校园信息核对(管理员) ---------------- */

/**
 * 待核对的校园信息列表(游标分页)。
 *
 * 契约 openapi.yaml:218 `/admin/verify/pending`,游标语义与机审队列不同:
 * 这里是 `cursor` + 响应里的 `nextCursor`,机审队列是 `lastId`。
 * 两个名字不能混 —— 传错名字后端拿到 null,静默返回第一页,
 * 于是「上拉加载更多」会反复追加同一页 20 条。
 *
 * 第一页不传 cursor(不是传 null):后端 `@RequestParam(required=false) Long cursor`
 * 收到空串会 400。
 *
 * @param {number|null} cursor 上一页最后一条的 userId
 * @param {number} size
 * @returns {Promise<{list: Array, nextCursor: number|null, hasMore: boolean}>}
 */
export function pendingVerify(cursor, size = 20) {
  const params = { size }
  if (cursor !== undefined && cursor !== null) params.cursor = cursor
  return request.get('/admin/verify/pending', { params })
}

/**
 * 标记校园信息已登记(verify_status 2 → 1)，只影响资料标记。
 *
 * `suppressStatuses: [404]` 见 utils/request.js:
 * 404 VERIFY_NOT_FOUND(1044) 表示「这条已被别的设备处置过」,
 * 调用方要把它翻译成人话,不能让全局红条先弹一遍。
 *
 * @param {number} userId
 * @returns {Promise<{message: string}>}
 */
export function approveVerify(userId) {
  return request.post(`/admin/verify/${userId}/approve`, null, { suppressStatuses: [404] })
}

/**
 * 标记校园信息需修改(verify_status 2 → 3)，只影响资料标记。
 *
 * reason 契约上限 200 字、必填(openapi §AdminVerifyRejectRequest)。
 * 上限不在前端截断 —— 输入框 maxlength=200 已经卡住,再截一次会让管理员
 * 以为自己写满了实际被悄悄砍掉。
 *
 * @param {number} userId
 * @param {string} reason 驳回理由,1~200 字
 */
export function rejectVerify(userId, reason) {
  return request.post(`/admin/verify/${userId}/reject`, { reason }, { suppressStatuses: [404] })
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
  pendingVerify,
  approveVerify,
  rejectVerify,
}
