import { computed } from 'vue'
import dayjs from 'dayjs'
import { capCount } from '@/composables/usePostDetail'

/**
 * 评论树(契约 `GET /post/{id}/comments` + `POST /post/{id}/comment`)
 * ------------------------------------------------------------------
 *
 * ## 契约的三个硬事实(全部来自 openapi.yaml,不是推测)
 *
 * 1. 列表端点**只返回一级评论**。二三级要前端按 `parentId` 自己嵌套。
 * 2. `parentId`:一级为 null;二级填**所回复的那条一级评论 id**;
 *    三级场景下该字段**不参与通知归属判定**(实现里只有 `level == 2` 才查 parentId)。
 * 3. `replyToUserId` = 被回复的人 id,服务端会校验「此人是否参与过本帖讨论」,
 *    不通过则**降级通知帖子作者且接口照样 200**。
 *
 * ## 由此推出的填法(这就是不触发降级的办法)
 *
 * 降级只在「replyToUserId 的人没在本帖说过话」时发生。而回复对象必然在本帖说过话
 * —— 他刚发了这条评论。所以**取被回复那条评论的作者 id,校验必然通过**。
 * 降级是给「前端 bug / 伪造 / 跨帖传 id」准备的兜底,不该是常态路径。
 *
 * 注意 `replyToUserId` 在**二级也要填**:通知归属二级走 `parentId` 查作者,
 * 但展示前缀 `回复 @xxx` 来自 `CommentVO.replyToNickname`,
 * 而后端 `toCommentVo` 是从 `c.getReplyToUserId()` 反查昵称的 ——
 * 二级不填这个字段,「回复 @xxx」前缀整条消失。
 */

/** 视觉缩进最多 2 层(设计 §4「禁止评论区楼层缩进超过 2 层」)。level 2 与 3 同缩进 */
export const MAX_VISUAL_DEPTH = 2

/**
 * 渲染递归的**硬上限**。与 MAX_VISUAL_DEPTH 分开:
 * 缩进上限是设计约束(视觉),这个是数据防御(环 / 超长链)。
 *
 * 脏数据能构出 A→B→A 的环,那种节点虽然被 buildCommentForest 归到了根,
 * 但彼此的 children 仍然互指 —— 递归渲染不设上限会栈溢出、整页白屏。
 * 到 3 层还没到头的,子级一律不渲染:宁可少显示几条,也不能让整页挂掉。
 */
export const MAX_RENDER_DEPTH = 3

/**
 * 归一化单条评论:格式化时间、补 children 容器。
 *
 * `user` 可能是 null(用户被删),`createTime` 是 `LocalDateTime` 序列化的时间串,
 * 两者都要挡住 —— 直接 `dayjs(undefined).format()` 会得到 "Invalid Date"。
 */
function normalize(c) {
  return {
    ...c,
    createTime: c.createTime ? dayjs(c.createTime).format('MM-DD HH:mm') : '',
    children: [],
  }
}

/**
 * 把扁平列表归一化成森林 + 建 id→node 索引。
 *
 * ## 孤儿评论为什么归到根而不是丢弃
 *
 * `parentId` 指向的父评论不在本页时(父在下一页、或父被删),如果丢弃这条评论,
 * 用户会看到「我明明回复了,评论不见了」。归到根至少内容还在、还能读到。
 * 真实后端目前只返回一级,这个分支暂时走不到,但**归一化必须先写对**,
 * 否则后端一放开二三级,前端要重写一遍。
 *
 * @param {Array<object>} list 接口返回的扁平评论
 * @returns {{roots: Array<object>, index: Map<number, object>}}
 */
export function buildCommentForest(list) {
  const index = new Map()
  for (const c of list || []) {
    if (c && c.id != null) index.set(c.id, normalize(c))
  }
  // 挂到父下,还是当根,按 hasParentInTree 判 —— 纯看 parentId 链,
  // 不依赖「谁先被处理」。环里 A→B→A 两条都会判定为 true,于是都进不了 children,
  // 落到 roots 平铺:内容宁可平铺也不能不显示。
  const roots = []
  for (const node of index.values()) {
    const parent = node.parentId != null ? index.get(node.parentId) : null
    if (parent && parent !== node && !chainReaches(node.id, parent.id, index)) {
      parent.children.push(node)
    } else {
      // 三种进根的情况,全都是「宁可平铺也不能不显示」:
      //   1. 没有父 / 父不在本页(孤儿)
      //   2. parentId 指向自己
      //   3. 挂上去会成环(A→B→A 的脏数据)
      roots.push(node)
    }
  }
  // 同一层内按 id 倒序(与后端 orderByDesc(id) 一致),子级同样倒序
  const byIdDesc = (a, b) => b.id - a.id
  roots.sort(byIdDesc)
  for (const node of index.values()) node.children.sort(byIdDesc)
  return { roots, index }
}

/**
 * 从 `startId` 沿 parentId 上溯,能不能走到 `targetId`。
 *
 * 沿 **parentId 链**走而不是 children —— parentId 是接口给的原始数据,
 * 与「谁已经挂好了」无关,所以结果与遍历顺序无关,环必然被识别出来。
 * 最多上溯 MAX_RENDER_DEPTH 层,超长链直接当成不成环(真实数据只有 3 层)。
 *
 * 为什么要判:真实后端不可能成环(`parentId` 由服务端从一级评论派生),
 * 但**前端不能假设数据干净**。成环时递归渲染会栈溢出、整页白屏,
 * 判一次的代价远低于白屏的代价。
 */
function chainReaches(startId, targetId, index) {
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
 * 三级回复的 `parentId` 必须填**一级祖先**而不是直接父级:
 * 契约说三级时 parentId 不参与通知判定,但它决定这条评论挂在哪棵树下——
 * 填直接父级会让渲染树多出一层(违反 2 层缩进上限),填一级祖先才与
 * 「二三级都挂在一级评论下」一致。
 *
 * @param {object} node 目标评论节点
 * @param {Map<number, object>} index buildCommentForest 返回的索引
 * @returns {number|null} 一级评论 id
 */
export function rootIdOf(node, index) {
  let cur = node
  let guard = 0
  // guard 兜底脏数据里的环(A.parentId=B 且 B.parentId=A),最多跳 10 层
  while (cur && cur.parentId != null && cur.level > 1 && guard < 10) {
    const p = index.get(cur.parentId)
    if (!p || p === cur) break
    cur = p
    guard += 1
  }
  return cur ? cur.id : null
}

/**
 * 回复某条评论时应提交的 `parentId` / `level` / `replyToUserId`。
 *
 * | 被回复的层级 | 发出层级 | parentId              | replyToUserId      |
 * |---|---|---|---|
 * | 一级 | 2 | 该一级评论自身 id | 该评论作者 id |
 * | 二级 | 3 | 其一级祖先 id | 该评论作者 id |
 * | 三级 | 3 | 其一级祖先 id | 该评论作者 id(封顶,不升 4) |
 * | 无 | 1 | null | null |
 *
 * 三级封顶的原因:契约 `level` 枚举只到 3,后端 `level > 3` 直接 400。
 * 封顶后语义变成「并入同一层的追问」,展示上因为 MAX_VISUAL_DEPTH = 2
 * 也与二级同缩进,读起来不突兀。
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

/**
 * 计数封顶。与页面其他计数同一口径。
 * 传的是**森林里的一级条数** —— 评论数是帖子级的 `commentCount`,
 * 这里只标题「评论 N」,用一级条数会与真实总数不符,所以调用方传 post.commentCount。
 */
export function commentCountText(n) {
  return capCount(n)
}

/**
 * 递归统计一棵子树里有多少条评论(含各级)。
 *
 * 用途:一级评论下折叠「展开 N 条回复」时,让用户知道折叠了多少。
 * @param {object} node
 * @returns {number}
 */
export function countSubtree(node) {
  if (!node) return 0
  return 1 + (node.children || []).reduce((n, ch) => n + countSubtree(ch), 0)
}

/**
 * 评论区的可见列表:森林 + 总条数。
 * @param {import('vue').Ref<Array<object>>} comments
 */
export function useCommentForest(comments) {
  const forest = computed(() => buildCommentForest(comments.value))
  return {
    roots: computed(() => forest.value.roots),
    index: computed(() => forest.value.index),
  }
}
