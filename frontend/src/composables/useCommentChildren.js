import { ref } from 'vue'
import { commentChildren } from '@/api/feed'

/**
 * 子回复溢出补齐(契约 `GET /post/{id}/comment/{commentId}/children`)
 * ------------------------------------------------------------------
 *
 * ## 为什么需要它
 *
 * 契约给 `children` 加了 20 条上限:超限时后端按 id 升序取**最老的** 20 条,
 * 把剩余条数放进 `childrenOverflowCount`,并明确要求**不得静默截断**。
 * 前端拿到 `childrenOverflowCount > 0` 就得给一个可点入口,否则用户看到的是
 * 「楼上明明有 19 条回复,怎么只显示 1 条」。
 *
 * ## 为什么要单独一个 composable 而不是塞进 PostComments
 *
 * 溢出是**按节点独立**的状态:每个一级评论有自己的游标、自己的加载中、
 * 自己的错误。把它们塞进组件意味着一个 `Map<id, state>` 加一堆模板分支,
 * 而 PostComments 还要管递归渲染和空态,两个关注点会互相干扰。
 * 这里返回 `stateOf(id)` 让组件按需问,状态只有一份。
 *
 * ## 失败不回滚已加载的
 *
 * 加载更多失败时**保留已显示的子回复**,只在入口上给一次重试。理由:
 * 用户已经读到了那 20 条,把整棵子树清空等于让他重新加载一遍才能继续读 ——
 * 比「多一条点不动」伤得多。契约没要求失败时的具体表现,这是我的判断。
 */
export function useCommentChildren(postId) {
  /**
   * 每个父评论 id → { loading, error, cursor, done, total }
   * total = 该节点的直接子回复总数(来自 totalForParent),不是已加载数
   */
  const states = ref(new Map())

  function stateOf(id) {
    return states.value.get(id) || null
  }

  /** 已加载数 = children 现有条数 + 溢出页已追加条数。由调用方算,这里不重复存 */
  function isOverflowed(node) {
    return Number(node?.childrenOverflowCount) > 0
  }

  /**
   * 加载某一节点的溢出子回复。
   *
   * @param {object} node 被溢出的父评论节点(需要有 id)
   * @returns {Promise<object[]>} 新追加的子回复节点数组(已 normalize)
   */
  async function loadMore(node) {
    const pid = node?.id
    if (pid == null) return []

    const prev = states.value.get(pid)
    // 正在加载 / 已全部加载完 —— 直接短路,避免连点触发重复请求
    if (prev?.loading || prev?.done) return []

    states.value.set(pid, {
      ...(prev || {}),
      loading: true,
      error: '',
      cursor: prev?.cursor ?? null,
    })

    try {
      // size 50 = 契约上限(maximum: 50),一次拉满,减少往返
      const data = await commentChildren(postId, pid, prev?.cursor ?? null, 50)
      const list = data?.list || []
      states.value.set(pid, {
        loading: false,
        error: '',
        // null 表示没有更多(契约:为 null 即没有更多)
        cursor: data?.nextCursor ?? null,
        done: data?.nextCursor == null,
        total: Number(data?.totalForParent) || prev?.total || 0,
      })
      return list
    } catch (e) {
      states.value.set(pid, {
        ...(prev || {}),
        loading: false,
        // 不动 cursor:重试时从同一页重来,不会跳条
        // 三档兜底,对应 request.js 的两条响应拦截路径:
        //   1. 业务错误 reject(new Error(message)) —— **不带 .response**,
        //      只写 e?.response?.data?.message 的话后端文案永远取不到
        //   2. HTTP 错误(4xx/5xx 走 onRejected)—— axios error,带 .response
        //   3. 网络错误/超时 —— 两者都没有
        error: e?.response?.data?.message || e?.message || '加载失败,点一下重试',
        done: false,
      })
      return []
    }
  }

  return { states, stateOf, isOverflowed, loadMore }
}
