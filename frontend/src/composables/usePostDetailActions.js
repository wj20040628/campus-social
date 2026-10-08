import { ref, computed, nextTick } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from '@/utils/feedback'
import {
  detail, like, unlike, deletePost,
  comments as commentsApi, comment as commentApi,
} from '@/api/feed'

/**
 * 帖子详情页的**有副作用**那一半(PostDetail.vue.md)
 * ------------------------------------------------------------------
 * 与 usePostDetail.js(纯计算层)配对:那边不碰网络,这边管请求与失败语义。
 *
 * 抽出来的原因不是洁癖,是行数门禁:模板 170 行 + 交互 150 行已经贴到 330,
 * 而设计 §1 的行 4(话题)/行 6(搭话)还没写进去。
 *
 * ## 一条贯穿的原则:失败必须能原样回滚
 *
 * 点赞是先改 UI 再发请求的乐观更新。失败时不能用「再点一次蒙对」,
 * 也不能只 toast 了事 —— 用户看到的是「赞没了但其实没取消成功」,
 * 比不做还糟。所以先记 wasLiked/wasCount,失败精确还原。
 */
export function usePostDetailActions(route, router) {
  const post = ref(null)
  const comments = ref([])
  const loading = ref(true)
  const loadFailed = ref(false)

  async function load() {
    loading.value = true
    loadFailed.value = false
    try {
      const data = await detail(route.params.id)
      post.value = { ...data, createTime: dayjs(data.createTime).format('MM-DD HH:mm') }
      await loadComments()
    } catch {
      // 帖子不存在与网络失败在这里不可区分(后端 404 与超时都是 catch 分支)。
      // 两种都归到「帖子不见了」+ 回首页 —— 用户能做的动作只有离开,
      // 给出「重试」在帖子不存在时是无意义的按钮。
      loadFailed.value = true
    } finally {
      loading.value = false
    }
  }

  async function loadComments() {
    const data = await commentsApi(route.params.id, null, 50)
    comments.value = (data.list || []).map((c) => ({
      ...c,
      createTime: dayjs(c.createTime).format('MM-DD HH:mm'),
    }))
    // 从通知跳来带 ?comment={id}:滚动并高亮到那条评论
    const cid = route.query.comment
    if (cid) nextTick(() => scrollToComment(cid))
  }

  function scrollToComment(cid) {
    const el = document.getElementById(`comment-${cid}`)
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('is-highlight')
    setTimeout(() => el.classList.remove('is-highlight'), 2500)
  }

  async function toggleLike() {
    if (!post.value) return
    const wasLiked = !!post.value.liked
    const wasCount = Number(post.value.likeCount) || 0
    post.value.liked = !wasLiked
    post.value.likeCount = Math.max(0, wasCount + (wasLiked ? -1 : 1))
    try {
      if (wasLiked) await unlike(route.params.id)
      else await like(route.params.id)
    } catch {
      // 原样回滚。计数也不能用「减一」糊弄 —— 后端可能有并发别人的赞,
      // 真实值要以服务端为准,所以恢复的是我们自己改之前的那个值
      post.value.liked = wasLiked
      post.value.likeCount = wasCount
      ElMessage.error('没点上，网络好像断了')
    }
  }

  /**
   * 发评论。**reject 时把 message 挂到 error.userMessage** ——
   * CommentComposer 靠它给出具体提示,而不是笼统的「没发出去」。
   */
  async function postComment({ content, mentionUserIds }) {
    try {
      // 被 @ 绑定的是选中时的唯一 id,后端按 id 精准发通知(昵称可能重名,不靠正文反查)
      await commentApi(route.params.id, { content, level: 1, mentionUserIds })
      post.value.commentCount = (post.value.commentCount || 0) + 1
      await loadComments()
    } catch (e) {
      const err = new Error('comment failed')
      // 三处兜底:业务错误(Error.message)、HTTP 错误(response)、网络错误(无 message)
      err.userMessage = e?.response?.data?.message || e?.message || '没发出去，再点一次发送'
      throw err
    }
  }

  async function remove() {
    try {
      await deletePost(route.params.id)
      ElMessage.success('已删除')
      router.push('/')
    } catch {
      /* request.js 已弹错,停在原地让用户重试 */
    }
  }

  /** 帖子作者 id。用于「编辑/删除 vs 搭句话」二选一 */
  const authorId = computed(() => post.value?.author?.id ?? null)
  /** 话题名列表。设计 §1 行 4 */
  const topics = computed(() => post.value?.topics || [])

  return {
    post,
    comments,
    loading,
    loadFailed,
    authorId,
    topics,
    load,
    toggleLike,
    postComment,
    remove,
  }
}