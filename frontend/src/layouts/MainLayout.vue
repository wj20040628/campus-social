<template>
  <div class="layout">
    <!--
      Skip link:仅键盘用户可达。TabBar + 侧栏在前面,
      键盘用户每个页面都要先穿过 5-6 个导航项才能到内容,这条是补救。
    -->
    <a class="skip-link" href="#main-content">跳到主内容</a>

    <div class="shell">
      <!-- 桌面侧栏;手机端 display:none,不进入可聚焦树 -->
      <SideNav class="layout__side" :route-path="route.path" />

      <div class="col">
        <!--
          ownTop:页面自带顶栏时不渲染这里的顶栏。
          用 v-if 而非 CSS 隐藏 —— 引导页(/onboarding)与公告编辑器
          的顶栏形态是「取消 / 进度 / 发布」,和全站顶栏的
          「品牌 / 搜索 / 铃铛」不是一回事,叠在一起会挤成两行 56px。
          (CSS 隐藏的 display:none 虽然不进 Tab 序,但 DOM 里留一份
          无用的 44x44 焦点位,键盘用户会 Tab 到看不见的按钮上。)
        -->
        <TopBar
          v-if="!ownTop"
          :show-back="showBack"
          :page-title="pageTitle"
          :notify-unread="notifyUnread"
          @logout="logout"
        />

        <main
          id="main-content"
          class="content"
          :class="{
            'content--no-tabbar': hideTabBar,
            'content--immersive': immersive,
            'content--own-top': ownTop,
          }"
          tabindex="-1"
        >
          <router-view v-slot="{ Component }">
            <!--
              **不要加 mode="out-in"**(实测:同一 URL `/user/undefined`,
              整页加载 kids=9/text=27 正常,SPA 导航 kids=0/text=0 永久空白,
              再点侧栏也恢复不了,只有 F5 能重置)。
              机制:out-in 要等旧组件 leave 完才挂新的,靠 transitionend 推进;
              组件在 leave 阶段抛错时收不到该事件,新组件永不挂载。
              去掉后组件抛错不再阻断挂载 —— 用户看到可用的错误页,而不是整片空白。

              KeepAlive 只缓存 Home(必须显式 name,见 Home.vue 头注释):
              从帖子返回时 feed 不重新挂载,配合 router scrollBehavior 的
              savedPosition 实现「退出帖子回到刚才的位置」。内容变更(发帖/
              编辑/删帖)后由 useFeedRefresh 的脏标记触发重拉,不会看到旧数据。
            -->
            <Transition name="ys-fade">
              <KeepAlive include="Home">
                <component :is="Component" />
              </KeepAlive>
            </Transition>
          </router-view>
        </main>
      </div>
    </div>

    <!--
      TabBar 用 v-if 而非 CSS display 切换:
      CSS 隐藏的元素仍然能被 Tab 键走到(display:none 不会,但 v-if 连 DOM 都不存在,
      两套外壳彻底隔离,不会各自维护一份焦点顺序)。
    -->
    <TabBar v-if="!hideTabBar" :paper-count="paperCount" />
  </div>
</template>

<script setup>
/**
 * 全站外壳(MainLayout.vue.md)
 *
 * 一套组件两副骨架:
 *   手机(<1024px):顶栏 56px + 底部 TabBar 56px + 内容区
 *   桌面(≥1024px):左侧 220px 侧栏(el-menu 保留)+ 顶栏 + 内容区
 *
 * 所有页面都继承它,改错一处全站受影响 —— 所以逻辑尽量少,
 * 复杂的部分拆成 SideNav.vue 与 TopBar.vue 两个子组件。
 *
 * 断点纪律:本文件只用字面量 `@media (min-width: 1024px)`,
 * 值取自 design-tokens.json 的 breakpoint.lg。
 * 本项目没有 postcss-custom-media,`@media (--ys-bp-lg)` 会被浏览器
 * 静默丢弃(SPEC §11 的「沉默故障」之一)。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import SideNav from './SideNav.vue'
import TopBar from './TopBar.vue'
import TabBar from '@/components/mobile/TabBar.vue'
import { useUserStore } from '@/stores/user'
import { getWsClient } from '@/utils/ws'
import * as notifyApi from '@/api/notify'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 顶栏形态与 TabBar 显隐全部由路由 meta 驱动,不在组件内判断路径 */
const showBack = computed(() => route.meta.back === true)
const hideTabBar = computed(() => route.meta.hideTabBar === true)
const pageTitle = computed(() => route.meta.title || '友声')
/** 页面自带顶栏(引导页 / 全屏编辑器)时不渲染全站顶栏 */
const ownTop = computed(() => route.meta.ownTop === true)

/**
 * 会话页(/chat/t/...)是沉浸式:输入区自己撑底,
 * 内容区不能再留 TabBar 高度 + 安全区,否则消息区会多出一段空白。
 */
const immersive = computed(() => route.name === 'chatThread')

const notifyUnread = ref(0)
const paperCount = ref(0)

/** 未读数是增强信息,拉取失败静默降级 —— 不该因为红点挂了而让用户以为系统坏了 */
async function loadUnread() {
  try {
    const data = await notifyApi.unread()
    notifyUnread.value = data?.unread ?? 0
  } catch {
    notifyUnread.value = 0
  }
}

/**
 * 待回应的搭话纸条数(TabBar 消息角标)。
 * 现阶段后端未提供独立计数端点,故先取 0 —— 角标为 0 时 TabBar 不渲染红点,
 * 不会给用户一个恒为 0 的假角标。后端补端点后改这里即可。
 */
async function loadPaperCount() {
  try {
    const res = await import('@/api/chat')
    const list = await res.conversations(0, 30)
    // conversations 里带 stranger 未回应标记才计数,当前契约无此字段,保持 0
    paperCount.value = Array.isArray(list) ? list.filter((c) => c?.pendingReply).length : 0
  } catch {
    paperCount.value = 0
  }
}

onMounted(() => {
  // 兜底:进布局时若 userInfo 还没加载(token 有效但没拉过),拉一次,
  // 保证顶栏头像 / 个人主页 / 退出都能用。
  if (userStore.hasToken && !userStore.userInfo) {
    userStore.fetchMe().catch(() => {})
  }
  if (userStore.hasToken) {
    loadUnread()
    loadPaperCount()
  }
})

async function logout() {
  try {
    await ElMessageBox.confirm('确定退出登录?', '提示', { type: 'warning' })
  } catch {
    return // 点了取消
  }
  getWsClient().close() // 断开 WS,避免收到已注销账号的推送
  try {
    await userStore.logout() // 吊销 refresh token + 清本地
    ElMessage.success('已退出登录')
    router.replace({ path: '/login/form' })
  } catch {
    // 即使后端调不通(如网络),也清本地并跳登录页 —— 不能把人困在已登录态
    userStore.clear()
    router.replace({ path: '/login/form' })
  }
}
</script>

<style scoped>
.layout {
  /* 移动浏览器 100vh 不含地址栏回缩高度,底部会被浏览器栏遮住。
     dvh 是动态视口高度,随地址栏收放实时变化。vh 保留作降级。 */
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--ys-bg);
}

.shell {
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
}

.layout__side {
  display: none;
}

.col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.content {
  flex: 1;
  min-width: 0;
  background: var(--ys-bg);
  padding: var(--ys-space-4) var(--ys-gutter-phone);
  /*
   * TabBar 是 position:fixed,内容区必须自己留出它的高度,
   * 否则最后一条内容永远被盖住。二者只能有一处负责 —— 就是这里。
   */
  padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom));
}

/* 页面自带顶栏时,内容区不加顶部内边距(顶栏由页面自己贴在最上) */
.content--own-top {
  padding-top: 0;
}

/* 不显示 TabBar 的页面:底部只留常规留白 + 安全区 */
.content--no-tabbar {
  padding-bottom: calc(var(--ys-space-4) + var(--ys-safe-bottom));
}

/* 沉浸式(会话页):输入区自己撑底,内容区不再加任何底部留白 */
.content--immersive {
  padding: 0;
  padding-bottom: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 键盘可达性:聚焦时显现。视觉隐藏但不能用 display:none(那样不可聚焦) */
.skip-link {
  position: absolute;
  left: -9999px;
  top: var(--ys-space-2);
  z-index: var(--ys-z-toast);
  padding: var(--ys-space-2) var(--ys-space-4);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  border-radius: var(--ys-radius-lg);
  font-size: var(--ys-text-sm);
  text-decoration: none;
}

.skip-link:focus {
  left: var(--ys-space-2);
}

/*
 * 桌面(≥1024px):显侧栏、隐 TabBar。
 * 用 display:none 而非 visibility:hidden —— 后者仍可被 Tab 键聚焦,
 * 会让键盘用户走到看不见的元素上(MainLayout.vue.md §5)。
 * 侧栏 sticky,长列表滚动时导航始终可见。
 */
@media (min-width: 1024px) {
  .layout__side {
    display: flex;
    position: sticky;
    top: 0;
    height: 100vh;
    height: 100dvh;
  }

  .content {
    padding: var(--ys-space-6) var(--ys-gutter-desktop) var(--ys-space-12);
  }

  .content--no-tabbar {
    padding-bottom: var(--ys-space-12);
  }
}

/* 路由过渡:只做 opacity + 8px 位移,不横向滑动(与浏览器返回手势冲突) */
.ys-fade-enter-active {
  transition: opacity var(--ys-motion-page) var(--ys-ease-in-out),
    transform var(--ys-motion-page) var(--ys-ease-in-out);
}

.ys-fade-leave-active {
  transition: opacity var(--ys-motion-fast) var(--ys-ease-in-out);
}

.ys-fade-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.ys-fade-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .ys-fade-enter-active,
  .ys-fade-leave-active {
    transition: none;
  }
}
</style>