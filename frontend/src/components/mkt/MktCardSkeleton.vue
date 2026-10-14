<template>
  <!-- 与 MktCard 同形的骨架:首屏加载时占位,避免高度跳变 -->
  <div class="mktcard mktcard__sk" aria-hidden="true">
    <div class="mktcard__top">
      <span class="sk__bar sk__bar--chip" />
      <span class="sk__bar sk__bar--timer" />
    </div>
    <span class="sk__bar sk__bar--title" />
    <span class="sk__bar sk__bar--text" />
    <div class="mktcard__info">
      <span class="sk__bar sk__bar--meta" />
    </div>
  </div>
</template>

<script setup>
/** MktCard 的骨架占位。形状对齐真实卡片(顶行 + 标题 + 预览 + 信息行)。 */
</script>

<style scoped>
.mktcard__sk {
  pointer-events: none;
}

.sk__bar {
  display: block;
  border-radius: var(--ys-radius-sm);
  background: var(--ys-surface-2);
  animation: sk-pulse 1.2s ease-in-out infinite;
}

.sk__bar--chip { width: 56px; height: 18px; }
.sk__bar--timer { width: 64px; height: 14px; }
.sk__bar--title { width: 80%; height: 20px; margin: var(--ys-space-3) 0; }
.sk__bar--text { width: 100%; height: 14px; }
.sk__bar--meta { width: 60%; height: 14px; }

/* 顶行/标题/文本/信息行复用 MktCard 的盒结构(padding 与 gap 保持一致) */
.mktcard__sk {
  display: flex;
  flex-direction: column;
  padding: var(--ys-space-4);
}
.mktcard__sk .mktcard__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.mktcard__sk .mktcard__info { margin-top: var(--ys-space-3); }

@keyframes sk-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}
</style>
