<template>
  <div class="safe-area" :class="`safe-area--${position}`" aria-hidden="true" />
</template>

<script setup>
/**
 * 安全区垫片(mobile-shell.md §1)
 * 只做垫片,不做布局。处理 iPhone Home Indicator 与 Android 手势条。
 *
 * 三条硬约束:
 * 1. 高度用 padding-bottom 而非 height,并用 max() 兜底 ——
 *    部分 Android WebView 下 env() 返回 0 但实际手势区更高,
 *    直接用 env() 会让 TabBar 最后一项落在系统手势条下方点不到。
 * 2. 必须放在**滚动容器之外** —— 放在滚动容器内,iOS 键盘弹起时安全区会跟着跳动。
 * 3. 桌面端渲染为 0 高度(env() 在桌面浏览器返回 0),无需额外媒体查询。
 */
defineProps({
  position: {
    type: String,
    default: 'bottom',
    validator: (v) => ['top', 'bottom'].includes(v),
  },
})
</script>

<style scoped>
.safe-area {
  flex: none;
  /* 纯装饰:不该被读屏朗读,也不该拦截点击 */
  pointer-events: none;
}

.safe-area--bottom {
  padding-bottom: max(var(--ys-safe-bottom), 8px);
}

.safe-area--top {
  padding-top: env(safe-area-inset-top, 0px);
}
</style>