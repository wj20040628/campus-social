import { like, unlike, favorite, unfavorite } from '@/api/feed'
import { ElMessage } from '@/utils/feedback'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

/**
 * 信息流卡片的点赞/收藏动作(2026-10-30 从 PostCard.vue 拆出)。
 *
 * 两个动作都是「乐观更新 + 失败回滚」的同一套语义；拆出来还顺手把
 * 「未登录先登录」收在一处，避免游客点一下才吃 401 再被跳转。
 * 游客的提示走点击弹框，页面上不放常驻横幅。
 */
export function usePostCardActions(post) {
  const { ensureLogin } = useGuestPrompt()

  /** feed 内直接点赞。乐观更新 + 失败回滚，与 usePostDetailActions.toggleLike 同逻辑:
   *  回滚恢复改之前的旧值,不做「减一」推算 —— 后端可能有并发点赞,真值以服务端为准 */
  async function toggleLike() {
    if (!(await ensureLogin('登录后才能点赞，现在去登录吗？'))) return
    const wasLiked = !!post.liked
    const wasCount = Number(post.likeCount) || 0
    post.liked = !wasLiked
    post.likeCount = Math.max(0, wasCount + (wasLiked ? -1 : 1))
    try {
      if (wasLiked) await unlike(post.id)
      else await like(post.id)
    } catch {
      post.liked = wasLiked
      post.likeCount = wasCount
      ElMessage.error('没点上,网络好像断了')
    }
  }

  /**
   * 收藏/取消收藏。与 toggleLike 完全同构:乐观更新 + 失败回滚到改之前的旧值。
   *
   * 回滚用「旧值」而不是「减一」:后端可能同时有别人在点,
   * 减一推算出来的数未必等于数据库真值,回滚必须回到自己改之前看到的那个数。
   */
  async function toggleFavorite() {
    if (!(await ensureLogin('登录后才能收藏，现在去登录吗？'))) return
    const wasFav = !!post.favorited
    const wasCount = Number(post.favoriteCount) || 0
    post.favorited = !wasFav
    post.favoriteCount = Math.max(0, wasCount + (wasFav ? -1 : 1))
    try {
      if (wasFav) await unfavorite(post.id)
      else await favorite(post.id)
    } catch {
      post.favorited = wasFav
      post.favoriteCount = wasCount
      ElMessage.error('没收藏上,网络好像断了')
    }
  }

  return { toggleLike, toggleFavorite }
}
