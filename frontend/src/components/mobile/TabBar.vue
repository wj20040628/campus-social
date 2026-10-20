<template>
  <nav class="tabbar" aria-label="主导航">
    <ul class="tabbar__list">
      <li v-for="item in items" :key="item.path" class="tabbar__item">
        <RouterLink
          :to="item.path"
          class="tabbar__link"
          :aria-current="isActive(item.path) ? 'page' : undefined"
          :aria-label="item.ariaLabel"
        >
          <span class="tabbar__icon">
            <el-icon :size="20"><component :is="item.icon" /></el-icon>
            <UnreadDot v-if="item.badge > 0" :count="item.badge" noun="张纸条待回应" />
          </span>
          <span class="tabbar__label" :class="{ 'is-active': isActive(item.path) }">
            {{ item.label }}
          </span>
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
 * 五个入口:首页 / 圈子 / 集市 / 消息 / 我的。
 * 中间第 3 项「集市」(校园限时活动)用凸起圆钮承载,与设计参考稿方向 A 的手机 TabBar 一致。
 * 发帖入口不在此:两端都走顶栏「+」(2026-10-20 移动端恢复),
 * TabBar 5 项锁定不可增减,因此把「发布」让位给「集市」。
 *
 * 关于第 4 项的角标语义:是**待回应的搭话纸条数**,不是私信未读数。
 * 纸条 24 小时未回应即消失,角标随之清零 —— 这与普通未读消息
 * 「永久累积」的语义不同,不要混(PRD 价值观:不回应必须是零成本)。
 *
 * 无障碍:
 * - nav + ul + li 语义结构
 * - 当前项用 aria-current="page",**不用纯颜色区分**(另加字重)
 * - 每项 aria-label 提供完整语义(「集市活动」而不是「集市」)
 */
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { House, ChatDotSquare, Shop, ChatDotRound, User } from '@element-plus/icons-vue'
import UnreadDot from './UnreadDot.vue'
import SafeArea from './SafeArea.vue'
import { useUserStore } from '@/stores/user'

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
    { path: '/', label: '首页', icon: House, ariaLabel: '首页', badge: 0 },
    { path: '/circles', label: '圈子', icon: ChatDotSquare, ariaLabel: '圈子列表', badge: 0 },
    { path: '/mkt', label: '集市', icon: Shop, ariaLabel: '集市活动广场', badge: 0 },
    { path: '/chat', label: '消息', icon: ChatDotRound, ariaLabel: '消息与纸条', badge: props.paperCount },
    { path: meId ? `/user/${meId}` : '/settings', label: '我的', icon: User, ariaLabel: '我的主页', badge: 0 },
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
  /* 56px 远超 AC-15 要求的 44px 触控下限 */
  min-height: var(--ys-tabbar-h);
  gap: 2px;
  color: var(--ys-muted);
  text-decoration: none;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}

.tabbar__link:hover {
  color: var(--ys-fg-2);
}

.tabbar__link[aria-current='page'] {
  color: var(--ys-accent);
}

.tabbar__icon {
  position: relative;
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
}

.tabbar__label {
  font-size: var(--ys-text-sm);
  line-height: 1.2;
  letter-spacing: var(--ys-ls-sm);
  font-weight: var(--ys-weight-regular);
}

/* 选中态只改字重不改尺寸 —— 改尺寸会让相邻项抖动 */
.tabbar__label.is-active {
  font-weight: var(--ys-weight-semibold);
}

/*
 * 中间项凸起(集市):必须用 transform,不能改 TabBar 高度。
 * 改高度会把顶栏顶穿,且影响所有继承 MainLayout 的页面。
 * 凸起圆钮是「集市」(校园活动广场)的一级入口视觉锚点,对齐设计参考稿方向 A。
 */
.tabbar__item:nth-child(3) .tabbar__icon {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  transform: translateY(-8px);
  box-shadow: var(--ys-shadow-2);
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