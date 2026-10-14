<template>
  <div class="home">
    <!--
      >=1280px 时双栏:主栏(feed)+ 右栏 HomeAside(公告速览/热搜/推荐圈子)。
      右栏只在 isWide 时渲染,组件内不做断点判断 —— 断点归 Home 管,
      右栏组件保持「给什么渲染什么」的哑组件。
    -->
    <div class="home-inner" :class="{ 'home-inner--wide': isWide }">
     <div class="home-main">
      <!--
        认证状态提示条(驳回 / 审核中)。
        放在**筛选条之上、Tab 之上**:被驳回是「你的账号有问题」级别的信息,
        夹在 feed 中间会被用户的下滑手势立刻扫掉,而且它描述的是账号不是内容 ——
        与下面「关注/全部」这个内容维度不是一回事,不该被 Tab 框住。
      -->
      <VerifyNotice />

      <!--
        公告入口(仅移动端):手机端唯一能进 /announcement 的路径 —— 桌面侧栏
        有「公告」项,但移动端侧栏 display:none,TabBar 五入口被 PRD 锁死不能加,
        顶栏已砍到只剩铃铛。故在首页 feed 之上放一条可点横幅,点开进公告列表。
        桌面端隐藏(侧栏已提供入口,避免重复)。
      -->
      <button
        v-if="isMobile"
        type="button"
        class="home-ann"
        @click="router.push('/announcement')"
      >
        <el-icon class="home-ann__icon" :size="18"><Promotion /></el-icon>
        <span class="home-ann__text">公告 · 查看最新通知</span>
        <el-icon class="home-ann__chev"><CaretRight /></el-icon>
      </button>

      <!--
        ③ 首页「关注/全部 tab + 搜索/筛选条」包成粘性工具条 .feed-bar。
        手机端:下滑收起(transform: translateY(-100%)),上滑拉回;VerifyNotice 留在上方不动。
        桌面端 feedBarHidden 恒 false,普通流。
      -->
      <div class="feed-bar" :class="{ 'feed-bar--hidden': feedBarHidden }">
        <!-- 关注 / 全部 tab(A1:读 feed_item 的写扩散收件箱) -->
        <el-tabs v-model="tab" class="feed-tabs">
          <el-tab-pane label="关注" name="following" />
          <el-tab-pane label="全部" name="all" />
        </el-tabs>

        <!-- 搜索 + 筛选/排序 工具条(仅"全部"tab 有筛选;关注 tab 只看关注的人) -->
        <div class="feed-tools">
          <el-input
            v-model="searchQ"
            placeholder="搜索帖子 / 用户 / 圈子"
            class="feed-search"
            clearable
            @keyup.enter="doSearch"
          >
            <template #append>
              <el-button @click="doSearch">搜索</el-button>
            </template>
          </el-input>

          <template v-if="tab === 'all'">
            <!--
              排序:「最新 / 最热」分段控件,替掉旧的两个下拉(按时间 + 倒序)。
              两个下拉表达一个语义,用户要逐字读完才知道怎么选;分段一眼可见。
              最热 = 按点赞量倒序,沿用旧 feed 接口的 by=like&order=desc。
            -->
            <div class="seg" role="group" aria-label="排序方式">
              <button type="button" class="seg__btn" :class="{ 'seg__btn--on': sortMode === 'new' }" :aria-pressed="sortMode === 'new' ? 'true' : 'false'" @click="setSort('new')">最新</button>
              <button type="button" class="seg__btn" :class="{ 'seg__btn--on': sortMode === 'hot' }" :aria-pressed="sortMode === 'hot' ? 'true' : 'false'" @click="setSort('hot')">最热</button>
            </div>
          </template>
        </div>
      </div>

      <div class="feed-list">
        <!-- 首次加载骨架屏 -->
        <template v-if="loading && posts.length === 0">
          <el-card class="skeleton-card" v-for="i in 3" :key="'sk' + i">
            <el-skeleton :rows="3" animated />
          </el-card>
        </template>
        <template v-else>
          <PostCard
            v-for="p in posts"
            :key="p.id"
            :post="p"
            @click="goDetail(p.id)"
          />
          <!--
空态按 tab 分两种出口(AC-19):
- 「关注」tab 空 → 去关注人(有 goFollow)
- 「发现」tab 空 → 去发一条或逛圈子
原 el-empty 只有描述没有出口,已替换。
-->
          <StateBlock
            v-if="!loading && posts.length === 0"
            variant="empty"
            :title="tab === 'following' ? '你关注的人还没发东西' : '这里还很空'"
            :description="
              tab === 'following'
                ? '关注几个同学,他们的动态就会出现在这儿'
                : '现在没人发东西,你可以先发一条'
            "
            :action-label="tab === 'following' ? '去圈子广场逛逛' : '发一条'"
            :icon="ChatLineSquare"
            @action="tab === 'following' ? goFollow() : router.push('/publish')"
          />
          <div ref="sentinelEl" class="sentinel">
            <el-text v-if="loading" type="info">加载中…</el-text>
            <el-text v-else-if="!hasMore && posts.length" type="info">到底了</el-text>
          </div>
        </template>
      </div>
     </div>
     <HomeAside v-if="isWide" :posts="posts" class="home-aside" />
    </div>
  </div>
</template>

<script setup>
/**
 * 首页 Feed。**name 必须是 'Home'**:MainLayout 用 <KeepAlive include="Home">
 * 缓存本页 —— 没有显式 name,include 匹配不到(文件名推断不参与 include 匹配),
 * 缓存失效,「退出帖子回到原位置」随之落空。
 */
defineOptions({ name: 'Home' })

import { ref, computed, onMounted, onActivated, onDeactivated, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import VerifyNotice from '@/components/common/VerifyNotice.vue'
import HomeAside from '@/components/HomeAside.vue'
import { ChatLineSquare, Promotion, CaretRight } from '@element-plus/icons-vue'
import { feed as feedApi, feedFollowing as followingApi } from '@/api/feed'
import { useVerifyStatus } from '@/composables/useVerifyStatus'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import { feedDirty } from '@/composables/useFeedRefresh'

const router = useRouter()

/**
 * 断点:xl(1280px)是右栏的开关。小于 1280 时右栏整个不渲染(v-if),
 * 不是 CSS 隐藏 —— 隐藏元素仍会被 Tab 键走到(MainLayout 同款理由)。
 */
const { width } = useBreakpoint()
const isMobile = computed(() => width.value < BREAKPOINTS.lg)
const isWide = computed(() => width.value >= BREAKPOINTS.xl)

const tab = ref('following') // 默认"关注"
const posts = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
let observer = null

// 认证状态是模块级单例,这里拿到的 load 与个人主页那边是同一个函数
const { load: loadVerifyStatus } = useVerifyStatus()
const sentinelEl = ref(null)

// 排序模式:new=最新(默认,可无限刷) / hot=最热(按点赞,只看第一页)
const searchQ = ref('')
const sortMode = ref('new')

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

function goDetail(id) {
  router.push(`/post/${id}`)
}

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
const savedScrollY = ref(0)

/** 组件进入 keep-alive 缓存前,记下当前滚动位置(供 onActivated 恢复) */
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
const feedBarHidden = ref(false)
let lastWinScrollTop = 0

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
</script>

<style scoped>
.home-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: 20px;
}
/*
 * >=1280px 双栏:主栏自适应 + 右栏 var(--ys-aside-w)(320px,token 里
 * 早就定义但一直没页面用,这次首页第一个用上)。1280 写字面量:
 * 没有 postcss-custom-media 时 @media(--ys-bp-xl) 会被浏览器静默丢弃,
 * 值取自 design-tokens.json 的 breakpoint.xl。
 */
@media (min-width: 1280px) {
  .home-inner--wide { max-width: 1024px; display: grid; grid-template-columns: minmax(0, 1fr) var(--ys-aside-w); gap: var(--ys-space-6); align-items: start; }
  .home-aside { position: sticky; top: var(--ys-space-6); }
}
/* 公告入口横幅:仅在移动端出现(桌面侧栏已有入口),宽度贴 home-inner,热区 ≥44px */
.home-ann {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  width: 100%;
  min-height: var(--ys-touch-min);
  padding: var(--ys-space-2) var(--ys-space-4);
  margin-bottom: var(--ys-space-3);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}
.home-ann:hover {
  border-color: var(--ys-accent);
  color: var(--ys-fg);
}
.home-ann__icon { flex: none; color: var(--ys-accent); }
.home-ann__text {
  flex: 1;
  min-width: 0;
  text-align: left;
  font-size: var(--ys-text-sm);
  font-family: var(--ys-font-body);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.home-ann__chev {
  flex: none;
  font-size: 14px;
  color: var(--ys-muted);
}
@media (min-width: 1024px) {
  .home-ann { display: none; }
}
.feed-tabs {
  margin-bottom: 8px;
}
/*
 * ③ 首页工具条(tab + 搜索/筛选条)「下滑收起 / 上滑拉回」。
 * feedBarHidden 仅在手机端由 window.scroll 驱动(桌面恒 false)。
 * 收起=translateY(-100%),贴顶隐藏;拉回=归位。
 * sticky:让上滑拉回时工具条贴住顶部(而不是滚出视口后留在原处)。
 */
.feed-bar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: var(--ys-bg);
  transition: transform 0.25s var(--ys-ease-out, ease-out);
}
.feed-bar--hidden {
  transform: translateY(-100%);
}
/*
 * 「关注 / 全部」两个 tab 的可点区:375px 实测 48x40,高度差 AC-15 的 44px 4 个像素。
 * el-tabs__item 是 EP 内部类,只能用 :deep 打进去。
 * 只给 min-height —— 横向已经 48 > 44,加 min-width 反而会把两个 tab 撑开、
 * 让「关注」离屏幕左边更远。
 */
.feed-tabs :deep(.el-tabs__item) {
  min-height: var(--ys-touch-min);
}
/*
 * 搜索框 append 里的「搜索」按钮:375px 实测 60x32。
 * 它与输入框同行,输入框本身够高,但按钮自身只有 32px,拇指点它偏小。
 * 只给 min-height,宽度 60 已过线。
 */
.feed-search :deep(.el-input-group__append .el-button) {
  min-height: var(--ys-touch-min);
}
.feed-tools {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  align-items: center;
}
.feed-search {
  flex: 1;
}
/*
 * 分段控件(最新/最热):暖纸底槽 + 白色激活胶囊。
 * 按钮 min-height 走 --ys-touch-min(44px):排序是高频操作,拇指要够大。
 */
.seg { flex: none; display: flex; gap: 2px; padding: 3px; background: var(--ys-surface-2); border-radius: var(--ys-radius-full); }
.seg__btn {
  border: 0; background: transparent; border-radius: var(--ys-radius-full);
  min-height: var(--ys-touch-min); padding: 0 var(--ys-space-4);
  font-family: var(--ys-font-body); font-size: var(--ys-text-sm); color: var(--ys-muted); cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out), color var(--ys-motion-fast) var(--ys-ease-out);
}
.seg__btn--on { background: var(--ys-surface); color: var(--ys-accent); font-weight: var(--ys-weight-semibold); box-shadow: var(--ys-shadow-1); }
.feed-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.skeleton-card {
  width: 100%;
}
.follow-hint {
  text-align: center;
  padding: 8px 0 16px;
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
