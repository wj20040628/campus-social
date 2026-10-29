/**
 * 举报理由枚举的唯一出处
 * ------------------------------------------------------------------
 * 【为什么要单独一个文件】
 * 这份枚举原先散在两处、各写一遍：
 *   - components/post/ReportSheet.vue  → REASONS（用户看到的选项）
 *   - composables/useReportQueue.js    → REASON_TEXT（管理员看到的文案）
 * 两边是同一个后端码（1–5），理应对得上；但**没有任何东西保证它们一致**。
 * 实际就漂移了：管理端写成「2不实信息 3广告营销 4违规内容」，
 * 于是用户报「色情」（码 3），管理员屏幕上显示「广告营销」。
 * 这类 bug 最难发现的地方在于：两边各自看着都自洽，只有交叉验证才暴露。
 * 管理员据此判断处置方向 —— 理由标签错了，等于依据错了。
 *
 * 【契约来源】docs/api/openapi.yaml §ReportSubmitRequest.reasonType
 * 后端只下发码、由前端渲染文案，所以这份映射必须自己守住。
 *
 * 【规矩】五个码的文案只在本文件出现一次。
 * 组件里禁止再写 `'色情'` 这类字面量 —— 一旦散开，改一处漏一处。
 */

/** 用户端举报表单的选项。顺序即展示顺序 */
export const REPORT_REASONS = [
  { v: 1, label: '骚扰' },
  { v: 2, label: '广告' },
  { v: 3, label: '色情' },
  { v: 4, label: '诈骗' },
  { v: 5, label: '其他' },
]

/**
 * 码 → 文案（管理端用）。
 *
 * 从 REPORT_REASONS 派生而不是另写一份 —— 结构上杜绝再次漂移。
 */
export const REASON_TEXT = Object.fromEntries(REPORT_REASONS.map((r) => [r.v, r.label]))

/** 兜底文案：后端将来加了新码而前端还没跟上时，显示它而不是空白 */
export const REASON_FALLBACK = '其他'

/** 码 → 文案，带兜底。管理端各处统一走这个函数 */
export function reasonLabel(code) {
  return REASON_TEXT[code] || REASON_FALLBACK
}
/**
 * 举报目标类型码 → 文案（2026-10-24 加，管理端举报卡用）。
 *
 * 【契约来源】docs/api/openapi.yaml §ReportVO.targetType，与后端
 * common/ReportTargetType 严格一致：1用户 2帖子 3评论 4私聊消息 5搭子
 * 6集市活动 7文件 8群消息 9圈子。
 * 「5 搭子」表已建无端点，正常不会出现在列表里，文案仍给全——
 * 万一出现，管理员看到的是「搭子(未上线)」而不是一个裸数字。
 *
 * 【规矩】同 reasonLabel：目标类型的文案只在这里出现一次，组件禁止写字面量。
 */
export const TARGET_TYPE_TEXT = {
  1: '用户',
  2: '帖子',
  3: '评论',
  4: '私聊消息',
  5: '搭子(未上线)',
  6: '集市活动',
  7: '文件',
  8: '群消息',
  9: '圈子',
}

/** 目标类型 → 可跳转的详情路由前缀；null = 没有可跳的详情页 */
export const TARGET_TYPE_ROUTE = {
  2: '/post/',
  6: '/mkt/',
  7: '/files/',
  9: '/circles/',
}

/** 码 → 文案，带兜底。管理端各处统一走这个函数 */
export function targetTypeLabel(code) {
  return TARGET_TYPE_TEXT[code] || '未知类型'
}
