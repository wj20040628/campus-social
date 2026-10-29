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
import { feed as feedApi, feedFollowing as followingApi, feedRecommend } from '@/api/feed'
import { feedDirty } from '@/composables/useFeedRefresh'

/** 首页 tab 记忆:只认这三个值,脏数据/旧版本残留一律回「关注」 */
const HOME_TAB_KEY = 'ys-home-tab'
const HOME_TABS = ['following', 'all', 'recommend']

function readHomeTab() {
  try {
    const v = localStorage.getItem(HOME_TAB_KEY)
    return HOME_TABS.includes(v) ? v : 'following'
  } catch {
    return 'following'
  }
}

function writeHomeTab(v) {
  try {
    localStorage.setItem(HOME_TAB_KEY, v)
  } catch {
    /* 无痕模式/配额满:记忆失败不影响当前 tab */
  }
}

export function useHomeFeed(isMobile) {
  const router = useRouter()

  const tab = ref(readHomeTab()) // 刷新后留在原 tab
  const posts = ref([])
  const cursor = ref(null)
  /** 推荐流专用游标:共同标签数。与 id 成对使用,缺一不可 */
  const cursorScore = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
  /*
   * 加载失败态（2026-10-21 加）。
   * loadError —— 首屏/翻页失败且已放弃自动重试，页面用它渲染错误态 + 重试按钮
   * failStreak —— 连续失败次数；达到 2 次就停止自动重试，避免无限循环。
   *   实测原来不限制时，拦一次接口会打出 200+ 个相同请求把页面拖死。
   */
  const loadError = ref(false)
  const failStreak = ref(0)
  const sentinelEl = ref(null)
  const savedScrollY = ref(0)
  const feedBarHidden = ref(false)
  let observer = null
  let lastWinScrollTop = 0

  /** 列表容器本体(Home 上 ref="listEl"),裁剪时用来量锚点位置 */
  const listEl = ref(null)
  /**
   * DOM 里最多保留多少条卡片。
   *
   * 200 是权衡结果:按每张卡 40-70 个 DOM 节点算,200 条约 1 万个节点,
   * 是中低端机能稳定滚动的量级;再往上(500 条约 3 万节点)低端安卓
   * 会明显掉帧,而且 KeepAlive 返回时要重新布局这一整片。
   */
  const MAX_POSTS = 200

  function normalize(list) {
    return (list || []).map((p) => ({
      ...p,
      /**
       * **不要把时间格式化成 'MM-DD HH:mm' 存进 createTime**（2026-10-21 修）。
       *
       * 原实现在这里就把 ISO 时间压成 "10-14 16:06" 字符串，导致下游
       * PostCard 无论怎么改都无法显示相对时间 —— relTime() 拿到
       * "10-14 16:06" 后 Date.parse 得到 NaN（Safari 尤其如此），
       * 只能原样回吐这个机器格式，于是列表里满眼都是 "09-15 11:35"。
       *
       * 时间格式化属于展示层，应该由组件（PostCard / relTime）决定，
       * 不该在数据归一化阶段做掉。这里只保证字段存在，不改它的值。
       */
      createTime: p.createTime,
      media: p.media || null,
    }))
  }

  function goFollow() {
    router.push('/search') // 搜索页找用户 → 进主页关注
  }

  /**
   * 追加一批帖子,并把总数压在 MAX_POSTS 以内。
   *
   * 为什么不能无限追加:posts 只增不减,DOM 里 PostCard 就跟着无限涨,
   * 而且 Home 在 <KeepAlive> 里,切到别的页面也不释放。翻到 400 条时
   * 页面上压着近 2 万个节点,滑动掉帧、从详情页返回时要重新布局一整片。
   *
   * 裁剪必须补滚动位置:从头部丢掉 k 条,文档整体上移 H 像素,而用户
   * 此刻正停在列表底部,不补的话画面会猛地往上跳一屏(内容全变了)。
   * 做法是拿「裁剪后仍在的第一条」当锚点,改完 DOM 把它挪回原来的
   * 屏幕位置。用 data-pid 定位而不是子元素下标 —— 列表里还会插入
   * 骨架屏、空态和哨兵,下标随时会错位。
   */
  async function appendCapped(add) {
    const merged = [...posts.value, ...add]
    const overflow = merged.length - MAX_POSTS
    if (overflow <= 0) {
      posts.value = merged
      return
    }
    const anchor = merged[overflow]
    const scrollBefore = window.scrollY || 0
    const pick = () => listEl.value?.querySelector(`[data-pid="${anchor.id}"]`) || null
    const anchorTopBefore = pick()?.getBoundingClientRect().top

    posts.value = merged.slice(overflow)

    if (anchorTopBefore == null) return
    await nextTick()
    const el = pick()
    if (!el) return
    // 目标滚动量 = 锚点当前文档位置 - 它裁剪前的视口位置。
    // 不写成「旧 scrollY + 高度差」:内容变矮时浏览器会先自行 clamp
    // scrollY,那一步已经吃掉一部分位移,只有两个视口坐标相减才准。
    const rect = el.getBoundingClientRect()
    const target = Math.max(0, rect.top + (window.scrollY || 0) - anchorTopBefore)
    if (Math.abs(target - scrollBefore) < 1) return
    window.scrollTo(0, target)
    // 这是程序化跳转,不是用户滑动:同步基准值,否则 onWinScroll 会把
    // 「scrollY 突然变小」判成上滑,把刚收起的工具条又弹回来。
    lastWinScrollTop = window.scrollY || 0
  }

  // 拉 feed(按当前 tab)
  async function loadFeed(reset = false) {
    if (reset) {
      posts.value = []
      cursor.value = null
      cursorScore.value = null
      hasMore.value = true
      loadError.value = false
      failStreak.value = 0
    }
    if (!hasMore.value || loading.value) return
    loading.value = true
    try {
      if (tab.value === 'following') {
        const data = await followingApi(cursor.value, 20)
        if (reset) posts.value = normalize(data.list)
        else await appendCapped(normalize(data.list))
        cursor.value = data.hasMore ? data.nextCursor : null
        hasMore.value = data.hasMore
      } else if (tab.value === 'recommend') {
        // 推荐:共同标签数 + 帖子 id 复合游标;无标签用户在服务端退化成最新
        const data = await feedRecommend(cursorScore.value, cursor.value, 20)
        if (reset) posts.value = normalize(data.list)
        else await appendCapped(normalize(data.list))
        cursor.value = data.hasMore ? data.nextId : null
        cursorScore.value = data.hasMore ? data.nextScore : null
        hasMore.value = data.hasMore
      } else {
        // 全部:固定按时间倒序,不再提供「最热」筛选
        const data = await feedApi(cursor.value, 20, { by: 'time', order: 'desc' })
        if (reset) posts.value = normalize(data.list)
        else await appendCapped(normalize(data.list))
        cursor.value = data.hasMore ? data.nextCursor : null
        hasMore.value = data.hasMore
      }
      loadError.value = false
      failStreak.value = 0
    } catch {
      /*
       * 失败处理（2026-10-21 修无限重试）。
       *
       * 【原来的问题】catch 里什么都不做，hasMore 仍是 true。
       * finally 里又 nextTick(setupObserver) 立刻重建 observer ——
       * 哨兵还在视口内 → 触发 onIntersect → 又调 loadFeed → 又失败……
       * 实测拦截一次接口会打出 200+ 个相同请求，页面冻结。
       *
       * 现在：连续失败 2 次就停下（hasMore=false，不再自动加载），
       * 并置 loadError，让页面显示错误态 + 「重试」按钮。
       * request.js 已经弹过一次 toast，这里不再重复弹，只改 UI 状态。
       */
      failStreak.value += 1
      if (failStreak.value >= 2 || reset) {
        hasMore.value = false
        loadError.value = true
      }
    } finally {
      loading.value = false
      nextTick(setupObserver)
    }
  }

  // tab 变化:先记下来,再重拉第一页
  watch(tab, (v) => {
    writeHomeTab(v)
    loadFeed(true)
  })

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
    tab, posts, loading, hasMore, loadError, sentinelEl, listEl, feedBarHidden,
    goFollow, retryFeed: () => loadFeed(true),
  }
}
