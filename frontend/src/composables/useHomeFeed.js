/**
 * 首页 feed 的全部状态与副作用(2026-10-15 从 Home.vue 拆出)。
 * ------------------------------------------------------------------
 * 拆出的理由:Home.vue 448 行超过 300 行门禁;且 feed 的数据逻辑
 * (拉取 / 游标 / 排序 / 无限滚动 / keep-alive 滚动恢复)与模板渲染
 * 无关,放 composable 里可以单独读、单独改。
 *
 * **拆的时候 keep-alive 四个生命周期钩子原样搬迁**(onMounted /
 * onActivated / onDeactivated / onBeforeUnmount 的调用顺序与内容
 * 一字未动)—— 那套「退出帖子回到刚才位置」的逻辑是实测调出来的,
 * 任何「顺手优化」都可能让位置恢复出错。回归只可能来自漏抄引用,
 * 已逐项核对。
 *
 * @param {ComputedRef<boolean>} isMobile 由 Home 传入(断点只有一个来源,
 *        不在 composable 里再开一个 resize 监听)
 */
import { ref, watch, nextTick, onMounted, onActivated, onDeactivated, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { feed as feedApi, feedFollowing as followingApi } from '@/api/feed'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { feedDirty } from '@/composables/useFeedRefresh'

export function useHomeFeed(isMobile) {
  const router = useRouter()

  const tab = ref('following') // 默认"关注"
  const posts = ref([])
  const cursor = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const sentinelEl = ref(null)
  // 排序模式:new=最新(默认,可无限刷) / hot=最热(按点赞,只看第一页)
  const searchQ = ref('')
  const sortMode = ref('new')
  const savedScrollY = ref(0)
  const feedBarHidden = ref(false)
  let observer = null
  let lastWinScrollTop = 0

  // 认证状态是模块级单例,这里拿到的 load 与个人主页那边是同一个函数
  const { load: loadVerifyStatus } = useVerifyStatus()

  function normalize(list) {
    return (list || []).map((p) => ({
      ...p,
      createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
      media: p.media || null,
    }))
  }

  // 搜索框回车 → 跳搜索结果页
  function doSearch() {
    const kw = searchQ.value.trim()
    if (!kw) return
    router.push({ path: '/search', query: { kw } })
  }

  function goFollow() {
    router.push('/search') // 搜索页找用户 → 进主页关注
  }

  // 拉 feed(按当前 tab / 筛选 / 方向)
  async function loadFeed(reset = false) {
    if (reset) {
      posts.value = []
      cursor.value = null
      hasMore.value = true
    }
    if (!hasMore.value || loading.value) return
    loading.value = true
    try {
      if (tab.value === 'following') {
        const data = await followingApi(cursor.value, 20)
        posts.value = reset ? normalize(data.list) : [...posts.value, ...normalize(data.list)]
        cursor.value = data.hasMore ? data.nextCursor : null
        hasMore.value = data.hasMore
      } else {
        // 全部 tab:「最热」是非默认筛选,只看第一页,不叠加无限刷(同旧逻辑)
        const filtered = sortMode.value !== 'new'
        const data = await feedApi(filtered ? (reset ? null : cursor.value) : cursor.value, 20, {
          by: sortMode.value === 'hot' ? 'like' : 'time',
          order: 'desc',
        })
        posts.value = !filtered && cursor.value && !reset
          ? [...posts.value, ...normalize(data.list)]
          : normalize(data.list)
        cursor.value = data.hasMore && !filtered ? data.nextCursor : null
        hasMore.value = data.hasMore && !filtered
      }
    } catch {
      // request.js 已弹错
    } finally {
      loading.value = false
      nextTick(setupObserver)
    }
  }

  /** 排序切换:最新 / 最热。点当前项不重拉 */
  function setSort(mode) {
    if (sortMode.value === mode) return
    sortMode.value = mode
    loadFeed(true)
  }

  watch(tab, () => loadFeed(true))

  function setupObserver() {
    observer?.disconnect()
    if (!sentinelEl.value) return
    observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !loading.value && hasMore.value) loadFeed(false)
      },
      { threshold: 0.1, rootMargin: '200px' }
    )
    observer.observe(sentinelEl.value)
  }

  onMounted(async () => {
    // 与 feed 并行发起,不 await:认证状态是附加信息,不该拖慢首屏。
    // 失败也不影响本页(useVerifyStatus 内部吞掉,不抛)。
    loadVerifyStatus()
    await loadFeed(true)
    nextTick(setupObserver)
    setupFeedBarScroll()
  })

  /**
   * keep-alive 激活(从帖子/发布页返回时触发,不再走 onMounted)。
   * 纯浏览返回:恢复离开时的滚动位置,数据原样保留(见下)。
   * 内容变更返回(发帖/编辑/删帖):重拉第一页,此时清列表跳顶符合预期。
   */
  onDeactivated(() => {
    savedScrollY.value = window.scrollY || window.pageYOffset || 0
    window.removeEventListener('scroll', onWinScroll)
  })

  /*
   * ③ 首页工具条「下滑收起 / 上滑拉回」(仅手机端)。
   * Home 在 <KeepAlive include="Home"> 里,body 级滚动,监听 window.scroll。
   * 下滑(往下刷帖)收起 .feed-bar,上滑(回看)拉回;近顶强制拉回。
   * 桌面端 isMobile=false,不监听,feedBarHidden 恒 false。
   */
  function setupFeedBarScroll() {
    if (!isMobile.value) return
    window.addEventListener('scroll', onWinScroll, { passive: true })
  }

  function onWinScroll() {
    if (!isMobile.value) return
    const st = window.scrollY || 0
    // 往上滑 或 已近顶 → 拉回
    if (st - lastWinScrollTop < 0 || st < 40) {
      feedBarHidden.value = false
    } else if (st > 80) {
      // 往下刷帖 → 收起
      feedBarHidden.value = true
    }
    lastWinScrollTop = st
  }

  onActivated(() => {
    // 手机端从 keep-alive 重新激活时,恢复工具条滚动监听(onDeactivated 已摘除)
    setupFeedBarScroll()
    if (feedDirty.value) {
      feedDirty.value = false
      loadFeed(true)
      return
    }
    // 纯浏览返回:恢复离开时的滚动位置。不依赖 vue-router 的 savedPosition
    // (它与 keep-alive + Transition 动画的配合时机不可靠,实测「返回位置不太准」),
    // 组件自己记住最准 —— 数据在缓存里原样保留,高度与离开时一致,直接回填即可。
    if (savedScrollY.value > 0) {
      nextTick(() => {
        requestAnimationFrame(() => {
          window.scrollTo(0, savedScrollY.value)
        })
      })
    }
  })

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', onWinScroll)
    observer?.disconnect()
    observer = null
  })

  return {
    tab, posts, loading, hasMore, searchQ, sortMode, sentinelEl, feedBarHidden,
    setSort, doSearch, goFollow,
  }
}
