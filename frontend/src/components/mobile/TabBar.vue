<template>
  <nav class="tabbar" aria-label="主导航">
    <ul class="tabbar__list">
      <li v-for="item in items" :key="item.path" class="tabbar__item">
        <RouterLink
          :to="item.path"
          class="tabbar__link"
          :class="{ 'is-active': isActive(item.path) }"
          :aria-current="isActive(item.path) ? 'page' : undefined"
          :aria-label="item.ariaLabel"
        >
          <span class="tabbar__icon">
            <NavIcon
              :name="item.icon"
              :size="22"
              :stroke-width="isActive(item.path) ? ACTIVE_STROKE : IDLE_STROKE"
            />
            <UnreadDot v-if="item.badge > 0" :count="item.badge" noun="条未读消息" />
          </span>
          <span class="tabbar__label">{{ item.label }}</span>
        </RouterLink>
      </li>
    </ul>
    <SafeArea position="bottom" />
  </nav>
</template>

<script setup>
/**
 * 移动端底部主导航(mobile-shell.md §2)
 *
 * 五个入口:首页 / 圈子 / 集市 / 消息 / 我的 —— **五项等权**。
 * 发帖入口不在此:两端都走顶栏「+」,TabBar 5 项锁定不可增减。
 *
 * [2026-10-20 改版] 去掉了中间「集市」的凸起圆钮,理由三条:
 * ① 凸起钮等于把「集市」抬成比首页更重的入口,但它不是主路径;
 * ② 凸起要 translateY(-8px),图标在 56px 条里上下不对称,和安全区一叠就歪;
 * ③ 设计稿评审时五项等权的版本明显更稳。图标同时从 EP 填充图标换成
 *    自绘描边(components/mobile/NavIcon.vue),与抽屉导航同一套口径。
 *
 * 关于第 4 项的角标语义:是**待回应的搭话纸条数**,不是私信未读数。
 * 纸条 24 小时未回应即消失,角标随之清零 —— 这与普通未读消息
 * 「永久累积」的语义不同,不要混(PRD 价值观:不回应必须是零成本)。
 *
 * 无障碍:
 * - nav + ul + li 语义结构
 * - 当前项用 aria-current="page",**不用纯颜色区分**(另加字重 + 图标加粗)
 * - 每项 aria-label 提供完整语义(「集市活动广场」而不是「集市」)
 */
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import NavIcon from './NavIcon.vue'
import UnreadDot from './UnreadDot.vue'
import SafeArea from './SafeArea.vue'
import { useUserStore } from '@/stores/user'

/** 未选中 1.9 / 选中 2.05:选中态靠线宽强化,不改图标尺寸(改尺寸会横向抖动) */
const IDLE_STROKE = 1.9
const ACTIVE_STROKE = 2.05

const props = defineProps({
  /**
   * 待回应的搭话纸条数。由父组件(MainLayout)从 chat store / API 取。
   * 默认 0 —— 为 0 时角标完全不渲染,不渲染一个「0」。
   */
  paperCount: { type: Number, default: 0 },
})

const route = useRoute()
const userStore = useUserStore()

const items = computed(() => {
  // 「我的」跳转个人主页 /user/:id。userInfo 是异步 fetchMe 的,未就绪时
  // id 为空 —— 此时给兜底 /settings,避免渲染出 /user/undefined 触发白屏
  // (TopBar.vue:141-146 已踩过这个 <Transition> patch parentNode 为 null 的坑)。
  // userInfo 就绪后 computed 自动重算,path 纠正为真实 /user/{id},无需手动刷新。
  const meId = userStore.userInfo?.id
  return [
    { path: '/', label: '首页', icon: 'home', ariaLabel: '首页', badge: 0 },
    { path: '/circles', label: '圈子', icon: 'board', ariaLabel: '圈子列表', badge: 0 },
    { path: '/mkt', label: '集市', icon: 'bag', ariaLabel: '集市活动广场', badge: 0 },
    { path: '/chat', label: '消息', icon: 'bubble', ariaLabel: '消息与纸条', badge: props.paperCount },
    { path: meId ? `/user/${meId}` : '/settings', label: '我的', icon: 'user', ariaLabel: '我的主页', badge: 0 },
  ]
})

/**
 * 选中判定用 startsWith 而非全等,理由:
 * /circles/12(圈子详情)应让「圈子」保持选中;/chat/t/dm/3(会话页)
 * 应让「消息」保持选中。全等会让进入二级页后 TabBar 突然全灭选中态。
 */
function isActive(path) {
  if (path === '/') return route.path === '/'
  return route.path === path || route.path.startsWith(path + '/')
}
</script>

<style scoped>
.tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--ys-surface);
  border-top: 1px solid var(--ys-border);
  z-index: var(--ys-z-sticky);
}

.tabbar__list {
  display: flex;
  margin: 0;
  padding: 0;
  list-style: none;
}

.tabbar__item {
  flex: 1 1 0;
  min-width: 0;
}

.tabbar__link {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  /* 项目没有全局 box-sizing 重置,内边距必须自己含进高度,
     否则 56 的 min-height 会被 6+6 撑成 68,内容区留白就对不上了 */
  box-sizing: border-box;
  min-height: var(--ys-tabbar-h);
  padding: 6px 2px;
  color: var(--ys-muted);
  text-decoration: none;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
  -webkit-tap-highlight-color: transparent;
}

/* 只在真有 hover 能力的设备上做悬停变色,触屏别留残影 */
@media (hover: hover) {
  .tabbar__link:hover {
    color: var(--ys-fg-2);
  }
}

.tabbar__link.is-active {
  color: var(--ys-accent);
}

.tabbar__icon {
  /* UnreadDot 是 position:absolute,必须有定位祖先,否则会跑到 .tabbar
     (fixed)的右上角 —— 角标一直挂错位置的根因就在这里 */
  position: relative;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
}

.tabbar__label {
  /* 10.5→11:设计稿按 390px 宽稿给的字号,11 在 5 等分(78px/项)下不折行 */
  font-size: 11px;
  line-height: 1.25;
  letter-spacing: 0.01em;
  font-weight: var(--ys-weight-regular);
}

/* 选中态只改字重不改字号 —— 改尺寸会让相邻项抖动 */
.tabbar__link.is-active .tabbar__label {
  font-weight: var(--ys-weight-semibold);
}

/* 尊重系统的减弱动效偏好 */
@media (prefers-reduced-motion: reduce) {
  .tabbar__link {
    transition: none;
  }
}

/*
 * 桌面(≥1024px):隐藏移动端 TabBar,导航交给左侧 SideNav。
 * MainLayout 的注释承诺了「桌面显侧栏、隐 TabBar」,这里补上隐藏规则。
 * 断点值取自 design-tokens.json 的 breakpoint.lg(1024),与 MainLayout 一致。
 */
@media (min-width: 1024px) {
  .tabbar {
    display: none;
  }
}
</style>
