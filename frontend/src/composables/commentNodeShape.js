import dayjs from 'dayjs'
import { MAX_RENDER_DEPTH } from './commentDepth'

/**
 * 节点整形：把接口返回的 CommentVO 转成渲染用的前端节点
 * ------------------------------------------------------------------
 *
 * ## 为什么独立于 useCommentTree
 *
 * 关注点不同：
 * - 本文件管「**单个节点长什么样**」：时间格式化、children 兜底、递归收集
 * - `useCommentTree` 管「**这些节点怎么挂成树**」：去重、判环、降级按 parentId 自建
 *
 * 混在一起时 `useCommentTree` 会同时承担数据整形与树结构两件事，
 * 而前者的改动（加一个格式化字段）不影响后者。
 */

/**
 * 归一化单条评论：格式化时间、兜底 children。
 *
 * ## 为什么 `children` 用 `Array.isArray` 而不是 `?? []`
 *
 * 后端 `CommentVO.children` 的 `default: []` 是**文档约定，不是 Jackson 的
 * 运行时行为** —— 若是 `null` 且类上没配 `@JsonInclude(NON_NULL)`，
 * 序列化出来就是 `"children": null`，不是 `[]`。
 *
 * 任何一条装配路径漏了初始化，下游 `parent.children.push(node)`
 * 就会抛 `Cannot read properties of null`，整页白屏。
 *
 * `?? []` 只挡 null/undefined，挡不住后端错返对象或字符串，所以用 `Array.isArray`。
 *
 * ## 另一处兜底
 *
 * `user` 可能是 null（用户被删），`createTime` 是 `LocalDateTime` 序列化的时间串，
 * 直接 `dayjs(undefined).format()` 会得到 "Invalid Date"。
 *
 * @param {object} c 接口返回的 CommentVO
 * @returns {object} 渲染用节点
 */
export function normalize(c) {
  return {
    ...c,
    createTime: c.createTime ? dayjs(c.createTime).format('MM-DD HH:mm') : '',
    // 拷贝而不是直接引用：children 随后会被 collect 往里 push 新节点，
    // 沿用接口原数组会把入参改掉（同一个响应对象被复用两次时互相污染）
    children: Array.isArray(c.children) ? c.children.slice() : [],
  }
}

/**
 * 递归收集一棵后端下发的子树，顺带 normalize 每个节点。
 *
 * ## 为什么要递归而不是只处理顶层
 *
 * 契约说 `children` 是「直接子回复，元素仍是 CommentVO（递归同构）」——
 * 二级的 children 就是三级。只处理顶层的话，三级节点进不了 index，
 * 回复三级评论时 `replySpecFor` 查不到人。
 *
 * **关键点：必须把 normalize 后的子节点写回父节点的 children。**
 * `normalize` 返回新对象（不污染入参），不回写的话父节点 children 里躺着的
 * 仍是接口原对象，而 index 里存的是另一个对象 —— 同一个 id 两个实例，
 * 渲染时读时间会拿到未格式化的原值（显示成 ISO 串）。
 *
 * ## 已在index 里的节点不返回 null，只补它的子树
 *
 * 返回 null 的话，`mergeExtra` 里对已加载节点的递归补齐就失效了——
 * 溢出页带回了某条已加载子回复的 grandchildren 时，那些三级永远进不了树。
 * 所以这里命中已存在时**继续往下走**，只是不返回节点本身（不重复 push）。
 *
 * @param {object} node 接口节点
 * @param {Map<number, object>} index id → 节点，就地填充
 * @param {number} [depth] 递归深度，上限 MAX_RENDER_DEPTH
 * @returns {object|null} 新建的节点；已存在时返回 null（子树仍会被补齐）
 */
export function collect(node, index, depth = 0) {
  if (!node || node.id == null) return null
  // 后端已保证三级封顶，这里再兜一层是为了防脏数据成环导致无限递归
  if (depth >= MAX_RENDER_DEPTH) return null
  const existing = index.get(node.id)
  const n = existing || normalize(node)
  if (!existing) index.set(n.id, n)
  // **从空数组开始**，不能沿用 n.children 里那批「待处理」的节点。
  // normalize 刚把 node.children 拷进 n.children，那里面装的正是接下来
  // 要递归的对象 —— 拿它建 seen 集合会把每一个子节点都判成「已处理」直接跳过，
  // 结果是整棵子树只收集到根（index 里只有一级）。
  const kids = []
  const seen = new Set()
  for (const ch of node.children || []) {
    if (!ch || ch.id == null || seen.has(ch.id)) continue
    seen.add(ch.id)
    const c = collect(ch, index, depth + 1)
    // c 为 null = 已在 index 里（子树已补齐）或超深度；两种都不 push 原对象——
    // 塞进去就绕开了 normalize，时间没格式化、replySpecFor 也查不到人
    if (c) kids.push(c)
  }
  if (existing) {
    // 已存在的节点：只在「新带回了更深一层」时才替换 children，
    // 否则会把它原有的子节点清空（每次重算都从接口原对象出发，原对象已被collect 改过）
    if (kids.length) n.children = kids.concat(n.children.filter((c) => !seen.has(c.id)))
    return null
  }
  n.children = kids
  return n
}