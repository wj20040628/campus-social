<template>
  <!-- 单条留言骨架:头像 + 昵称条 + 正文条 -->
  <div class="mkcsk" aria-hidden="true">
    <span class="mkcsk__avatar" />
    <div class="mkcsk__body">
      <span class="mkcsk__bar mkcsk__bar--nick" />
      <span class="mkcsk__bar mkcsk__bar--text" />
    </div>
  </div>
</template>

<script setup>
/** MktComments 留言骨架。形状对齐真实留言项(头像 + 昵称 + 正文)。 */
</script>

<style scoped>
.mkcsk {
  display: flex;
  gap: var(--ys-space-3);
}

.mkcsk__avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  animation: sk-pulse 1.2s ease-in-out infinite;
}

.mkcsk__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.mkcsk__bar {
  border-radius: var(--ys-radius-sm);
  background: var(--ys-surface-2);
  animation: sk-pulse 1.2s ease-in-out infinite;
}

.mkcsk__bar--nick { width: 40%; height: 12px; }
.mkcsk__bar--text { width: 90%; height: 14px; }

@keyframes sk-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}
</style>
