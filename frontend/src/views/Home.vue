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

    <!--
      移动端浮动发帖按钮:
      - 中位 TabBar 被「集市」占据,原本「发布」入口让位给一级导航,
        但发帖是高频动作,完全失去入口会让用户找不到发东西的地方
      - 56×56 圆形,默认落点:右下角(距底 88px 避开 TabBar 56px + 安全区,
        距右 16px 留手侧空间);可拖拽,松手后吸附到最近屏幕边缘
      - 位置持久化 localStorage,freshHome=true 时(KeepAlive 回来)保留位置
      - 桌面端隐藏(顶栏已有「+」)
    -->
    <button
      v-if="isMobile"
      ref="fabEl"
      type="button"
      class="home-fab"
      :class="{ 'is-dragging': fab.dragging }"
      :style="{ left: fab.x + 'px', bottom: fab.y + 'px' }"
      :aria-label="'去发布'"
      @touchstart="handlers.onTouchStart"
      @touchmove="handlers.onTouchMove"
      @touchend="handlers.onTouchEnd"
      @click="handlers.onClick"
      @pointerdown="handlers.onPointerDown"
      @pointermove="handlers.onPointerMove"
      @pointerup="handlers.onPointerUp"
    >
      <el-icon :size="24"><Plus /></el-icon>
      <!-- 拖拽提示:按钮下方淡字,只在拖动中显示,避免误触 -->
      <span v-if="fab.dragging" class="home-fab__hint">拖动</span>
    </button>
  </div>
</template>

<script setup>
/**
 * 首页 Feed。**name 必须是 'Home'**:MainLayout 用 <KeepAlive include="Home">
 * 缓存本页 —— 没有显式 name,include 匹配不到(文件名推断不参与 include 匹配),
 * 缓存失效,「退出帖子回到原位置」随之落空。
 *
 * 2026-10-15 拆分:feed 的全部状态与副作用搬去 composables/useHomeFeed.js
 * (拉取/游标/排序/无限滚动/keep-alive 滚动恢复,生命周期原样搬迁),
 * 样式外置去 Home.css。本文件只剩模板与编排,从 448 行降到 ~150 行。
 */
defineOptions({ name: 'Home' })

import { computed } from 'vue'
import { useRouter } from 'vue-router'
import PostCard from '@/components/PostCard.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import VerifyNotice from '@/components/common/VerifyNotice.vue'
import HomeAside from '@/components/HomeAside.vue'
import { ChatLineSquare, Promotion, CaretRight, Plus } from '@element-plus/icons-vue'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import { useHomeFeed } from '@/composables/useHomeFeed'
import { useDraggableFab } from '@/composables/useDraggableFab'

const router = useRouter()

/**
 * 断点:xl(1280px)是右栏的开关。小于 1280 时右栏整个不渲染(v-if),
 * 不是 CSS 隐藏 —— 隐藏元素仍会被 Tab 键走到(MainLayout 同款理由)。
 */
const { width } = useBreakpoint()
const isMobile = computed(() => width.value < BREAKPOINTS.lg)
const isWide = computed(() => width.value >= BREAKPOINTS.xl)

const {
  tab, posts, loading, hasMore, searchQ, sortMode, sentinelEl, feedBarHidden,
  setSort, doSearch, goFollow,
} = useHomeFeed(isMobile)

// 移动端浮动发帖按钮:可拖拽 + 边缘吸附 + 位置持久化(见 useDraggableFab)
const { fabEl, fab, handlers } = useDraggableFab()

function goDetail(id) {
  router.push(`/post/${id}`)
}
</script>

<style scoped src="./Home.css"></style>
