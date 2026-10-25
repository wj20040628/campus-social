/**
 * 机审状态（review_task.status）文案的唯一出处
 * ------------------------------------------------------------------
 * 与后端 ReviewServiceImpl / ReviewTask 的取值一致：0 待审 · 1 已通过 · 2 已驳回。
 *
 * 【为什么码 0 有两套措辞，而 1/2 只有一套】
 * 码 0 是同一个状态在两种视角下的不同说法：
 *   - 作者看自己的投稿 → 「审核中」（我的内容在处理，等着就好）
 *   - 管理员看队列     → 「待审」  （这里有一件活等我干）
 * 这两个词都对，抹平任何一个都会丢掉视角信息，所以**刻意保留差异**。
 * 但 1/2 是纯结论（已通过 / 已驳回），两种视角没有区别，
 * 必须同源 —— 否则就会像举报理由那样：同一码在两处渲染成不同的话。
 *
 * 【为什么单独立文件】原先这份映射散在 MyReviewBlock.vue 与 AdminReview.vue，
 * 各写一份且没有任何东西保证 1/2 一致。这里把「相同的那部分」收敛到一处，
 * 把「不同的那部分」显式写出来 —— 有意的差异应当看起来就是有意的。
 */

/** 作者视角 */
export const REVIEW_STATUS_TEXT = {
  0: '审核中',
  1: '已通过',
  2: '已驳回',
}

/**
 * 管理员视角。只覆写码 0，其余沿用上面那份 ——
 * 这样 1/2 永远不可能与作者端漂移。
 */
export const REVIEW_STATUS_TEXT_ADMIN = {
  ...REVIEW_STATUS_TEXT,
  0: '待审',
}

/**
 * 取文案。
 * @param {number} s review_task.status
 * @param {boolean} admin 是否管理员视角
 */
export function reviewStatusLabel(s, admin = false) {
  const table = admin ? REVIEW_STATUS_TEXT_ADMIN : REVIEW_STATUS_TEXT
  return table[s] || '未知'
}