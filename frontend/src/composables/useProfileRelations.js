import { ref, watch } from 'vue'
import {
  status, follow as followApi, unfollow as unfollowApi,
  followers as followersApi, following as followingApi,
} from '@/api/relation'
import { useUserStore } from '@/stores/user'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

/**
 * 个人主页的关注关系与粉丝 / 关注列表。
 *
 * 抽出来的原因:主页模板已接近 300 行门禁,而这块有 60 多行状态与副作用。
 * 它还有**独立的两条失败语义**(关注操作失败 vs 列表拉取失败),
 * 混在主页里容易被写成「一失败全页报错」。
 *
 * 关注失败的语义(设计 §3):按钮回到原状态 + 已由 request.js 弹出的错,
 * **不整页替换** —— 关注失败不该让人以为这个主页挂了。
 *
 * @param {import('vue').Ref<number>} targetId 目标用户 id(路由参数)
 */
export function useProfileRelations(targetId) {
  const userStore = useUserStore()
  const { ensureLogin } = useGuestPrompt()
  const relStatus = ref(null)
  const followLoading = ref(false)

  const followers = ref([])
  const following = ref([])
  const loadingFollowers = ref(false)
  const loadingFollowing = ref(false)

  async function loadRelation() {
    // 游客只读:关系接口要登录，跳过请求，主页头按「未关注」渲染
    if (!userStore.hasToken) {
      relStatus.value = null
      return
    }
    relStatus.value = await status(targetId.value)
  }

  /**
   * 关注 / 取关。
   *
   * 乐观更新:先改 UI 再发请求 —— 否则点一下要等一个往返才看到变化,
   * 在移动端网络下这个延迟会被感知成「没点上」。失败时回滚。
   *
   * @returns {Promise<'followed'|'unfollowed'|null>} 三态而不是布尔。
   *   布尔不够用:`false` 同时意味着「取关成功」与「操作失败」,
   *   调用方会在这两种情况下做出完全相反的计数改动 —— 一次失败的关注
   *   会把粉丝数减 1。这是典型的沉默逻辑错误,所以用三态区分。
   */
  async function toggleFollow() {
    if (!userStore.hasToken || followLoading.value) return null
    const wasFollowing = !!relStatus.value?.following
    followLoading.value = true
    relStatus.value.following = !wasFollowing
    try {
      if (wasFollowing) {
        await unfollowApi(targetId.value)
        return 'unfollowed'
      }
      await followApi(targetId.value)
      return 'followed'
    } catch {
      // 回滚到操作前状态。不再弹一次错:request.js 已经弹过,重复打扰
      relStatus.value.following = wasFollowing
      return null
    } finally {
      followLoading.value = false
    }
  }

  /**
   * 列表随主页一起预拉(不再惰性等点 Tab)。
   *
   * 原先:进主页默认停在「动态」tab,粉丝/关注列表要等你点那个 tab 才发请求;
   * 点过去时请求还没回来,UserList 先按 [] 渲染空态「还没关注任何人」,
   * 刷新后重新触发拉取才显示 —— 用户看到的是「明明有、却不显示」,得刷一次才好。
   *
   * 现在:切人时(路由复用)跟 loadRelation 一起并发预拉两个列表,
   * 进主页那一刻数据就到位,空态不再抢跑。并发去重仍靠 loading + 有缓存早退。
   */
  async function loadFollowers() {
    if (!userStore.hasToken) return
    if (loadingFollowers.value || followers.value.length) return
    loadingFollowers.value = true
    try {
      const data = await followersApi(targetId.value, null, 50)
      followers.value = (data?.list || []).filter(Boolean)
    } finally {
      loadingFollowers.value = false
    }
  }

  async function loadFollowing() {
    if (!userStore.hasToken) return
    if (loadingFollowing.value || following.value.length) return
    loadingFollowing.value = true
    try {
      const data = await followingApi(targetId.value, null, 50)
      following.value = (data?.list || []).filter(Boolean)
    } finally {
      loadingFollowing.value = false
    }
  }

  /** 点 Tab 头触发。列表已在进主页时预拉,这里只兜底补拉(失败/没缓存) */
  function onTabChange(name) {
    if (name === 'followers') loadFollowers()
    if (name === 'following') loadFollowing()
  }

  /** 切人(路由复用)时全清。不清会残留上一个用户的关注关系与列表 */
  function reset() {
    relStatus.value = null
    followers.value = []
    following.value = []
  }

  // 路由复用时组件不重建,靠 watch 重置,并重拉关系 + 预拉两个列表(并发)。
  watch(targetId, () => {
    reset()
    loadRelation().catch(() => {})
    loadFollowers()
    loadFollowing()
  })

  return {
    relStatus, followLoading, followers, following,
    loadingFollowers, loadingFollowing,
    loadRelation, toggleFollow, onTabChange, reset,
    loadFollowers, loadFollowing, ensureLogin,
  }
}