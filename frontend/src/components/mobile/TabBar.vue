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
 * 五个入口由 PRD F1 锁定,不可增减。顺序与图标见 mobile-shell.md §2.2。
 *
 * 关于第 4 项的角标语义:是**待回应的搭话纸条数**,不是私信未读数。
 * 纸条 24 小时未回应即消失,角标随之清零 —— 这与普通未读消息
 * 「永久累积」的语义不同,不要混(PRD 价值观:不回应必须是零成本)。
 *
 * 无障碍:
 * - nav + ul + li 语义结构
 * - 当前项用 aria-current="page",**不用纯颜色区分**(另加字重)
 * - 每项 aria-label 提供完整语义(「发布动态」而不是「发布」)
 */
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { House, ChatDotSquare, Plus, ChatDotRound, User } from '@element-plus/icons-vue'
import UnreadDot from './UnreadDot.vue'
import SafeArea from './SafeArea.vue'

const props = defineProps({
  /**
   * 待回应的搭话纸条数。由父组件(MainLayout)从 chat store / API 取。
   * 默认 0 —— 为 0 时角标完全不渲染,不渲染一个「0」。
   */
  paperCount: { type: Number, default: 0 },
})

const route = useRoute()

const items = computed(() => [
  { path: '/', label: '首页', icon: House, ariaLabel: '首页', badge: 0 },
  { path: '/circles', label: '圈子', icon: ChatDotSquare, ariaLabel: '圈子列表', badge: 0 },
  { path: '/publish', label: '发布', icon: Plus, ariaLabel: '发布动态', badge: 0 },
  { path: '/chat', label: '消息', icon: ChatDotRound, ariaLabel: '消息与纸条', badge: props.paperCount },
  { path: '/settings', label: '我的', icon: User, ariaLabel: '我的主页与设置', badge: 0 },
])

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
 * 中间项凸起(发布):必须用 transform,不能改 TabBar 高度。
 * 改高度会把顶栏顶穿,且影响所有继承 MainLayout 的页面。
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
</style>