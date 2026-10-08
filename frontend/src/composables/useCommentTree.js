import { computed } from 'vue'
import { capCount } from '@/composables/usePostDetail'
// 判环（chainReaches）与「回复时该传什么」（rootIdOf / replySpecFor）已拆到
// useCommentReplySpec.js —— 那两个是回复交互的领域逻辑，与树构建是两个关注点。
// 本文件仍要用 chainReaches：挂载前确认不会形成环。
import { chainReaches } from './useCommentReplySpec'
// 节点整形（normalize / collect）已拆到 commentNodeShape.js —— 那是「单个节点
// 长什么样」，与本文件「节点怎么挂成树」是两个关注点。
import { collect, normalize } from './commentNodeShape'
// 深度常量独立成文件：本文件、useCommentReplySpec、commentNodeShape 三者共用
// (若让后两者从本文件取会形成循环依赖)
import { MAX_RENDER_DEPTH, MAX_VISUAL_DEPTH } from './commentDepth'

/**
 * 评论树(契约 `GET /post/{id}/comments` + `POST /post/{id}/comment`)
 * ------------------------------------------------------------------
 *
 * ## 契约的三个硬事实(全部来自 openapi.yaml,不是推测)
 *
 * 1. 列表端点返回**一级评论 + 每条的 `children` 子树**（三级封顶，每节点上限 20 条，
 *    超限条数在 `childrenOverflowCount`，不得静默截断）。溢出补齐走
 *    `GET /post/{id}/comment/{commentId}/children`，见 `useCommentChildren.js`。
 *    **历史**：本注释早期写的是「只返回一级评论，二三级由前端按 parentId 自己嵌套」——
 *    那是错的，后端照它实现导致二三级在任何端点都取不到。已在契约 57e20e1 更正。
 * 2. `parentId`：一级为 null；二级填**所回复的那条一级评论 id**；
 *    三级填**其一级祖先 id**（不是被回复评论的 id，否则树里多出一层）。
 *    三级场景下该字段**不参与通知归属判定**(实现里只有 `level == 2` 才查 parentId)。
 * 3. `replyToUserId` = 被回复的人 id，服务端会校验「此人是否参与过本帖讨论」，
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
 *
 * ## 溢出懒加载不在本文件
 *
 * 按节点独立的状态（各自的游标 / 加载中 / 错误）由 `useCommentChildren.js` 承担。
 * 塞进来会让本文件同时管「树怎么长」和「溢出怎么补」两件事。
 */

/**
 * 把接口返回的评论列表转成渲染用的森林 + id→node 索引。
 *
 * ## 两种数据来源,优先用后端给的
 *
 * 契约补了 `children` 后,**正常路径是后端直接下发整棵树** —— 每条评论的
 * `children` 就是它的直接子回复,前端只需要递归 normalize 一下(排时间序、
 * 兜 children)。这时**不能**再按 parentId 重建:后端已经把父子关系组装好,
 * 重建会① 抹掉 childrenOverflowCount ② 在 `parentId` 语义有歧义时挂错位置。
 *
 * 但自建分支不能删,三个场景仍需要:
 * 1. **溢出补齐页**:`GET .../children` 只返回直接子回复,不带 grandchildren,
 *    其中若有三级,仍要靠 parentId 挂回二级。
 * 2. **降级容错**:某个后端版本没下发 children 时,靠 parentId 也能把二级/三级
 *    拼出来(就是早先的实现),行为退化但不白屏。
 * 3. **孤儿评论**:`parentId` 指向的父不在本页(父在下一页/被删)时,
 *    归到根而不是丢弃 —— 否则用户会看到「我明明回复了,评论不见了」。
 *
 * @param {Array<object>} list 接口返回的评论列表(顶层为一级)
 * @param {Map<number, Array<object>>|null} [extraByParent] 溢出页并入用:父 id → 额外子回复
 * @returns {{roots: Array<object>, index: Map<number, object>}}
 */
export function buildCommentForest(list, extraByParent = null) {
  const index = new Map()
  const raw = (list || []).filter((c) => c && c.id != null)
  if (raw.length === 0) return { roots: [], index }

  // 递归 normalize 后端下发的子树(有 children 的走这条)
  for (const c of raw) collect(c, index)

  // 降级路径:所有顶层都是 level=1 且没有任何 children —— 说明这份响应
  // 是「只给一级」的旧形状(或某个后端版本没下发 children)。
  // 这时按 parentId 自建,行为退化但不白屏。
  const hasAnyChildren = raw.some((c) => Array.isArray(c.children) && c.children.length)
  const forest = hasAnyChildren ? null : attachByParentId(raw.map((c) => normalize(c)))
  if (forest) {
    return mergeExtra(forest, extraByParent)
  }

  // 正常路径:顶层各自是一棵树的根。顺序沿用后端给的
  // (契约:一级按 id 倒序),不按 parentId 重排 —— 后端已组装好父子关系。
  const roots = []
  for (const c of raw) {
    const n = index.get(c.id)
    if (n) roots.push(n)
  }
  return mergeExtra({ roots, index }, extraByParent)
}

/**
 * 把「溢出补齐页取回的子回复」并进已建好的森林。
 *
 * ## 为什么不直接 push 到 node.children
 *
 * 森林是 computed 每次重算的产物,外部改它的节点,下一次 props 变化就被覆盖 ——
 * 表现是「点开加载了,刷新一下又只剩 20 条」。把额外节点作为**入参**传进来,
 * 让它们在建树阶段就被合并,是唯一能活过重算的做法。
 *
 * ## 去重
 *
 * 溢出页的第一页可能与 children 里的 20 条有重叠(后端按 id 游标分页,
 * 边界可能落在已给的条目上)。按 id 去重,重复的丢弃 —— 同一个 id 渲染两次
 * 会让「共 N 条」和实际条数对不上。
 *
 * @param {{roots: Array<object>, index: Map<number, object>}} forest
 * @param {Map<number, Array<object>>|null} extraByParent 父 id → 额外子回复
 */
function mergeExtra(forest, extraByParent) {
  if (!extraByParent || extraByParent.size === 0) return forest
  const { index } = forest
  for (const [parentId, list] of extraByParent) {
    const parent = index.get(parentId)
    if (!parent || !Array.isArray(list)) continue
    const seen = new Set(parent.children.map((c) => c.id))
    for (const raw of list) {
      if (!raw || raw.id == null || seen.has(raw.id)) continue
      // collect 对「已在 index 里」的节点会继续递归补它的子树但返回 null,
      // 所以这里只在拿到新节点时才 push —— 已加载的那条不重复渲染,
      // 但它带回来的更深一层(grandchildren)会被补进树里。
      const node = collect(raw, index)
      if (node) {
        parent.children.push(node)
        seen.add(node.id)
      }
    }
    // 溢出页取回的是「最新的 20 条」,并到已有 children 后统一按 id 倒序:
    // 契约说 children 取最老的 20 条,新取的自然更新,放头部才符合
    // 「按 id 倒序 = 最新在前」的既有阅读顺序。
    parent.children.sort((a, b) => b.id - a.id)
  }
  return forest
}

/**
 * 递归收集一棵后端下发的子树,顺带 normalize 每个节点。
 *
 * 为什么要递归而不是只处理顶层:契约说 `children` 是「直接子回复,元素仍是
 * CommentVO(递归同构)」—— 二级的 children 就是三级。只处理顶层的话,
 * 三级节点进不了 index,回复三级评论时 `replySpecFor` 查不到人。
 *
 * **关键点:必须把 normalize 后的子节点写回父节点的 children。**
 * `normalize` 是返回新对象的(不污染入参),如果不回写,父节点 children 里
 * 躺着的仍是接口原对象,而 index 里存的是另一个对象 —— 同一个 id 对应两个
 * 实例,渲染时读格式化时间会拿到未格式化的原值(显示成 ISO 串)。
 *
 * `MAX_RENDER_DEPTH` 同时用作收集深度上限:后端已保证三级封顶,
 * 这里再兜一层是为了防脏数据成环导致无限递归。
 */

/**
 * 按 parentId 把**扁平**列表挂成森林。
 *
 * 保留这个函数是因为 `buildCommentForest` 的降级路径要用它(见上)。
 * 判环沿 parentId 链,与遍历顺序无关 —— 环里 A→B→A 两条都判定为不成环,
 * 于是都进不了 children,落到 roots 平铺:内容宁可平铺也不能整段消失。
 */
function attachByParentId(nodes) {
  const index = new Map(nodes.map((n) => [n.id, n]))
  const roots = []
  for (const node of nodes) {
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
  // 自建时后端没排过序,得自己排:与契约一致(同层 id 倒序 = 最新在前)。
  // 正常路径(后端下发树)不排 —— 那边已在服务端排好,重排会丢「取最老的
  // 那 20 条」的顺序(契约对溢出明确要求按 id 升序取前 20)。
  const byIdDesc = (a, b) => b.id - a.id
  roots.sort(byIdDesc)
  for (const node of nodes) node.children.sort(byIdDesc)
  return { roots, index }
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
