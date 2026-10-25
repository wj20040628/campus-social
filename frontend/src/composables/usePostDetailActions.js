import { ref, computed, nextTick } from 'vue'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { buildCommentForest } from '@/composables/useCommentTree'
import { replySpecFor } from '@/composables/useCommentReplySpec'
import { useUserStore } from '@/stores/user'
import {
  detail, like, unlike, deletePost, deleteComment as deleteCommentApi,
  favorite as favoriteApi, unfavorite as unfavoriteApi,
  comments as commentsApi, comment as commentApi,
} from '@/api/feed'
import { markFeedDirty } from '@/composables/useFeedRefresh'

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

  const userStore = useUserStore()

  /**
   * 游客点互动的引导(2026-10-25)。
   *
   * 详情页是分享落地页,游客扫码进来就能看到点赞/收藏按钮。不放拦截的话,
   * 点下去是 401 → request.js 走 refresh 失败 → 全局登出流程踢去登录页,
   * 像出了故障。改为点之前先弹说明框,愿意登录就带 returnUrl 跳过去。
   *
   * @returns {Promise<boolean>} true = 已登录可继续;false = 游客(已弹框,本次动作终止)
   */
  async function ensureLogin() {
    if (userStore.hasToken) return true
    try {
      await ElMessageBox.confirm(
        '登录之后才能点赞和收藏，现在去登录吗？',
        '登录后可点赞',
        { type: 'info', confirmButtonText: '去登录', cancelButtonText: '先逛逛' },
      )
    } catch {
      return false
    }
    router.push({ path: '/login/form', query: { returnUrl: route.fullPath } })
    return false
  }

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

  /**
   * 拉评论。**这里只存扁平列表,不建树** ——
   * 树在 buildCommentForest(渲染时 computed)里建,存两份会让「刷新后顺序变了」这类问题
   * 变成两份数据不一致,排查时看不出是哪一份错了。
   *
   * size 50 是契约上限(cursor page maximum: 50),取满一页而不是 20:
   * 绝大多数帖子评论不过 50,一次拉够就不用做「加载更多」的交互。
   */
  async function loadComments() {
    const data = await commentsApi(route.params.id, null, 50)
    comments.value = data.list || []
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
    if (!(await ensureLogin())) return
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
   *
   * ## 层级与被回复者怎么填(契约 CreateCommentDTO)
   *
   * 关键点:`replyToUserId` 取**被回复那条评论的作者 id**,不是当前用户、不是路人。
   * 服务端会校验「此人是否参与过本帖讨论」,不通过则降级通知帖子作者且接口照样 200。
   * 取对了就必然通过 —— 回复对象刚在这条评论里说过话,他是参与者。
   * 降级是给伪造/误传兜底的,不该是常态路径。
   *
   * @param {{content:string, mentionUserIds?:number[], replyTo?:object|null}} payload
   */
  async function postComment({ content, mentionUserIds, replyTo }) {
    try {
      // replyTo 是 CommentRow 传上来的**节点引用**,层级要用整棵树的索引回溯,
      // 所以这里重新建一次森林而不是在组件里算 —— 组件里没有全量列表。
      const { index } = buildCommentForest(comments.value)
      const spec = replySpecFor(replyTo || null, index)
      // 被 @ 绑定的是选中时的唯一 id,后端按 id 精准发通知(昵称可能重名,不靠正文反查)
      await commentApi(route.params.id, {
        content,
        level: spec.level,
        parentId: spec.parentId,
        replyToUserId: spec.replyToUserId,
        mentionUserIds,
      })
      post.value.commentCount = (post.value.commentCount || 0) + 1
      await loadComments()
    } catch (e) {
      const err = new Error('comment failed')
      // 三处兜底,对应 request.js 的两条响应拦截路径:
      //   1. 业务错误(code != 200)→ reject(new Error(message)),**不带 .response**,
      //      所以第一档取不到,真实文案在 e.message
      //   2. HTTP 错误(4xx/5xx 走 onRejected)→ axios error,带 .response
      //   3. 网络错误/超时 → 两者都没有,落兜底文案
      // 顺序不能颠倒:把 e.message 放前面会让 HTTP 错误显示后端的笼统文案
      err.userMessage = e?.response?.data?.message || e?.message || '没发出去，再点一次发送'
      throw err
    }
  }

  /**
   * 删除自己的评论(2026-10-24 新增,配后端 DELETE /post/{id}/comment/{commentId})。
   * 确认框在调用方(PostDetail):删除是子树级不可逆操作,评论+回复一起移除。
   * 成功后重拉评论与计数(计数后端已按删除行数扣)。
   */
  /** 收藏/取消收藏(2026-10-24 补详情页入口):乐观更新+失败回滚,与 toggleLike 同款 */
  async function toggleFavorite() {
    if (!post.value) return
    if (!(await ensureLogin())) return
    const wasFav = !!post.value.favorited
    const wasCount = Number(post.value.favoriteCount) || 0
    post.value.favorited = !wasFav
    post.value.favoriteCount = Math.max(0, wasCount + (wasFav ? -1 : 1))
    try {
      if (wasFav) await unfavoriteApi(route.params.id)
      else await favoriteApi(route.params.id)
    } catch {
      post.value.favorited = wasFav
      post.value.favoriteCount = wasCount
      // request.js 已弹错
    }
  }

  async function removeComment(commentId) {
    try {
      await deleteCommentApi(route.params.id, commentId)
      ElMessage.success('评论已删除')
      await loadComments()
      if (post.value.commentCount > 0) post.value.commentCount -= 1
    } catch {
      /* request.js 已弹错,停在原地 */
    }
  }

  async function remove() {
    /*
     * 删帖确认(2026-10-24 补):不可逆操作,此前点了直接删——
     * 操作条上「删除」与「编辑」相邻,移动端误触一下整帖就没了。
     * 用 ElMessageBox 与圈管理解散圈同款形态(低频重操作,弹窗值得)。
     */
    try {
      await ElMessageBox.confirm(
        '删除后这篇动态和它的评论、点赞都会消失，无法恢复。',
        '删除动态',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '再想想' },
      )
    } catch {
      return // 取消 = 什么都不做,留在原页
    }
    try {
      await deletePost(route.params.id)
      ElMessage.success('已删除')
      markFeedDirty() // 首页 keep-alive 缓存里还有这篇,标脏让返回时重拉
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
    remove, removeComment, toggleFavorite,
  }
}