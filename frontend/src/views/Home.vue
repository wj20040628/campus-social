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
        公告横幅已移除（2026-10-21，用户要求）。
        原注释说它是「手机端唯一能进 /announcement 的路径」—— 那个判断是错的：
        手机端顶栏有汉堡按钮，打开 AppDrawer 后「公告」就在导航里（见
        AppDrawer.vue 的导航项 { label: '公告', path: '/announcement' }）。
        所以删掉首页横幅不会让公告页失联，只是少一处重复入口。
      -->

      <!--
        ③ 首页「关注/全部/推荐」tab 包成粘性工具条 .feed-bar。
        游客只开放「全部」，不渲染需要身份的关注/推荐 tab。
      -->
      <div v-if="!isGuest" class="feed-bar" :class="{ 'feed-bar--hidden': feedBarHidden }">
        <!-- 关注 / 全部 / 推荐。树洞频道已下线,历史匿名帖仍在「全部」里只读展示 -->
        <el-tabs v-model="tab" class="feed-tabs">
          <el-tab-pane label="关注" name="following" />
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane label="推荐" name="recommend" />
        </el-tabs>
      </div>

      <div ref="listEl" class="feed-list">
        <!-- 首次加载骨架屏 -->
        <template v-if="loading && posts.length === 0">
          <el-card class="skeleton-card" v-for="i in 3" :key="'sk' + i">
            <el-skeleton :rows="3" animated />
          </el-card>
        </template>
        <template v-else>
          <!-- data-pid 是裁剪时定位锚点用的(见 useHomeFeed.appendCapped),
               不是样式钩子,别改成 class -->
          <PostCard
            v-for="p in posts"
            :key="p.id"
            :data-pid="p.id"
            :post="p"
            @click="goDetail(p.id)"
          />
          <!--
            错误态（2026-10-21 加）：接口失败时优先于空态显示。
            【为什么需要】原来 catch 里什么都不做，posts 为空且 loading=false，
            于是落到下面的空态，显示「你关注的人还没发东西」——
            把**网络故障**说成了**没人发内容**，用户会以为平台本来就没东西。
            现在先判断 loadError，给「没加载出来 + 重试」，语义才对。
          -->
          <StateBlock
            v-if="!loading && loadError && posts.length === 0"
            variant="error"
            title="动态没加载出来"
            description="可能是网络断了，点一下重试"
            action-label="重试"
            :icon="WarningFilled"
            @action="retryFeed()"
          />

          <!--
            空态按 tab 分出口:
            - 「关注」空 → 去关注人;「推荐」空 → 去发一条
            - 「全部」空 → 去发一条
          -->
          <StateBlock
            v-else-if="!loading && posts.length === 0"
            variant="empty"
            :title="tab === 'following' ? '你关注的人还没发东西' : tab === 'recommend' ? '还没有可推荐的动态' : '这里还很空'"
            :description="
              tab === 'following'
                ? '关注几个同学,他们的动态就会出现在这儿'
                : tab === 'recommend'
                  ? '去关注几个同学,或者先发一条'
                  : '现在没人发东西,你可以先发一条'
            "
            :action-label="tab === 'following' ? '去圈子广场逛逛' : '发一条'"
            :icon="ChatLineSquare"
            @action="tab === 'following' ? goFollow() : goPublish()"
          />
          <div ref="sentinelEl" class="sentinel">
            <el-text v-if="loading" type="info">加载中…</el-text>
            <el-text v-else-if="!hasMore && posts.length" type="info">到底了</el-text>
          </div>
        </template>
      </div>
     </div>
     <HomeAside v-if="isWide && !isGuest" :posts="posts" class="home-aside" />
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
import HomeAside from '@/components/HomeAside.vue'
import { ChatLineSquare, WarningFilled } from '@element-plus/icons-vue'
import { useBreakpoint, BREAKPOINTS } from '@/composables/useBreakpoint'
import { useHomeFeed } from '@/composables/useHomeFeed'
import { useDraggableFab } from '@/composables/useDraggableFab'
import { useUserStore } from '@/stores/user'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

const router = useRouter()
const userStore = useUserStore()
const isGuest = computed(() => userStore.isGuest)
const { ensureLogin } = useGuestPrompt()

/**
 * 断点:xl(1280px)是右栏的开关。小于 1280 时右栏整个不渲染(v-if),
 * 不是 CSS 隐藏 —— 隐藏元素仍会被 Tab 键走到(MainLayout 同款理由)。
 */
const { width } = useBreakpoint()
const isMobile = computed(() => width.value < BREAKPOINTS.lg)
const isWide = computed(() => width.value >= BREAKPOINTS.xl)

const {
  tab, posts, loading, hasMore, loadError, sentinelEl, listEl, feedBarHidden,
  goFollow, retryFeed,
} = useHomeFeed(isMobile)

// 移动端浮动发帖按钮:可拖拽 + 边缘吸附 + 位置持久化(见 useDraggableFab)
const { fabEl, fab, handlers } = useDraggableFab()

function goDetail(id) {
  router.push(`/post/${id}`)
}
/** 空态「发一条」:游客先弹登录提示,再进发布页 */
async function goPublish() {
  if (!(await ensureLogin('登录后才能发帖，现在去登录吗？'))) return
  router.push('/publish')
}
</script>

<style scoped src="./Home.css"></style>
