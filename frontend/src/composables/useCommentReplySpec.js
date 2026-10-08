/**
 * 回复规格计算：回复某条评论时应提交的 `parentId` / `level` / `replyToUserId`
 * ------------------------------------------------------------------
 *
 * ## 为什么独立于 useCommentTree
 *
 * 两个关注点：
 * - `useCommentTree` 管「**树怎么长**」：归一化、按 parentId 挂载、判环、去重
 * - 本文件管「**回复时该传什么**」：回溯一级祖先、算层级、决定通知对象
 *
 * 调用方也天然分开：`PostComments.vue` / `CommentRow.vue` 只用树，
 * `usePostDetailActions.js` 只用本文件的 `rootIdOf` / `replySpecFor`。
 */

import { MAX_RENDER_DEPTH } from './commentDepth'

/**
 * 从 `startId` 沿 parentId 链能否到达 `targetId`。
 *
 * 用途：挂载前确认不会造成环。数据里若已存在
 * A.parentId=B 且 B.parentId=A，不查会形成死循环导致页面卡死。
 * 遍历深度封顶 `MAX_RENDER_DEPTH`，脏数据下也不会无限跳。
 *
 * 与遍历顺序无关 —— 只看链，所以「谁先被挂载」不影响判定结果。
 *
 * @param {number} startId 起始 id
 * @param {number} targetId 目标 id
 * @param {Map<number, object>} index
 * @returns {boolean}
 */
export function chainReaches(startId, targetId, index) {
  let cur = index.get(targetId)
  for (let i = 0; i < MAX_RENDER_DEPTH; i += 1) {
    if (!cur) return false
    if (cur.id === startId) return true
    cur = cur.parentId != null ? index.get(cur.parentId) : null
  }
  return false
}

/**
 * 回溯到所属的一级评论 id。
 *
 * ## 三级回复的 `parentId` 为什么填「一级祖先」而不是直接父级
 *
 * 契约里 `parentId` 只写了「所回复的评论 id」，**没写三级时该填哪一级**
 * （team-lead 已确认这是契约缺口，正在补）。前端按下述规则填，并与后端对齐：
 * - 回一级 → `parentId` = 该一级评论 id，`level=2`
 * - 回二级 → `parentId` = 其**一级祖先** id，`level=3`
 * - 回三级 → `parentId` = 其**一级祖先** id，`level=3`（封顶不升 4）
 *
 * 填直接父级会让三级在树里多出一层，前端渲染成 4 层缩进，
 * 违反设计 §4「禁止评论区楼层缩进超过 2 层」。
 *
 * ## 为什么沿 parentId 链回溯而不是沿 children 找根
 *
 * 两者在数据一致时等价，但 parentId 链是**提交时要用的同一个字段** ——
 * 沿它回溯能保证「算出来的 parentId」与「树里实际的挂载关系」同源。
 * 沿 children 找根在 `parentId` 缺失或存错时会给出另一个答案，
 * 那种情况下两条路径不一致，更难排查。
 *
 * @param {object} node 目标评论节点
 * @param {Map<number, object>} index buildCommentForest 返回的索引
 * @returns {number|null} 一级评论 id
 */
export function rootIdOf(node, index) {
  let cur = node
  let guard = 0
  // guard 兜脏数据里的环(A.parentId=B 且 B.parentId=A)，最多跳 MAX_RENDER_DEPTH 层
  while (cur && cur.parentId != null && cur.level > 1 && guard < MAX_RENDER_DEPTH) {
    const p = index.get(cur.parentId)
    if (!p || p === cur) break
    // 后端树里若出现「往回跳」也当断链处理：level 变大了说明数据乱了
    if (p.level >= cur.level) break
    cur = p
    guard += 1
  }
  return cur ? cur.id : null
}

/**
 * 回复某条评论时应提交的 `parentId` / `level` / `replyToUserId`。
 *
 * | 被回复的层级 | 发出层级 | parentId| replyToUserId      |
 * |---|---|---|---|
 * | 一级 | 2 | 该一级评论自身 id | 该评论作者 id |
 * | 二级 | 3 | 其一级祖先 id | 该评论作者 id |
 * | 三级 | 3 | 其一级祖先 id | 该评论作者 id（封顶，不升 4） |
 * | 无 | 1 | null | null |
 *
 * 三级封顶的原因：契约 `level` 枚举只到 3，后端 `level > 3` 直接 400。
 * 封顶后语义变成「并入同一层的追问」，展示上因为 MAX_VISUAL_DEPTH = 2
 * 也与二级同缩进，读起来不突兀。
 *
 * ## 为什么 `replyToUserId` 取被回复评论的 `userId`（降级必然不触发）
 *
 * 服务端 `resolveLevel3Target` 会校验「此人是否参与过本帖讨论」，
 * 不通过则**降级通知帖子作者且接口照样 200**。
 * 但回复对象必然在本帖说过话 —— 他刚发了这条评论。所以校验**必然通过**。
 * 降级是给「前端 bug / 伪造 / 跨帖传 id」准备的兜底，不该是常态路径。
 *
 * @param {object|null} target 被回复的评论节点
 * @param {Map<number, object>} index
 * @returns {{parentId: number|null, level: number, replyToUserId: number|null}}
 */
export function replySpecFor(target, index) {
  if (!target) return { parentId: null, level: 1, replyToUserId: null }
  const level = Math.min((Number(target.level) || 1) + 1, 3)
  return {
    parentId: level === 1 ? null : rootIdOf(target, index),
    level,
    replyToUserId: target.userId ?? null,
  }
}