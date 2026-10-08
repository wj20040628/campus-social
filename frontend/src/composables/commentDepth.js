/**
 * 评论树的深度上限。独立成文件是因为三个模块都要它：
 * `useCommentTree`（树构建）、`useCommentReplySpec`（回溯一级祖先）、
 * `commentNodeShape`（递归收集的深度兜底）。
 *
 * 若让后两者从 `useCommentTree` import，会与它import 那两个形成循环依赖
 * —— ESM 能解析，但 rollup 打包时行为不稳，且常量本就不该依赖树实现。
 */

/** 视觉缩进最多 2 层（设计 §4「禁止评论区楼层缩进超过 2 层」）。level 2 与 3 同缩进 */
export const MAX_VISUAL_DEPTH = 2

/**
 * 渲染深度上限：后端保证三级封顶，这里兜一层防脏数据成环导致无限递归。
 *
 * **注意与 MAX_VISUAL_DEPTH 是两个独立约束**：
 * - RENDER_DEPTH 管「数据结构最多递归几层」（安全边界）
 * - VISUAL_DEPTH 管「界面上最多缩进几层」（设计约束）
 * 三级评论在结构上是第 3 层，在视觉上与二级同缩进 —— 两者不可混谈。
 */
export const MAX_RENDER_DEPTH = 3