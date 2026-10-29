<template>
  <!-- 管理待办红色角标：0 不渲染，99+ 封顶 -->
  <span v-if="count > 0" class="apb ys-num" role="status" :aria-label="`${count} 项未处理`">
    {{ count > 99 ? '99+' : count }}
  </span>
</template>

<script setup>
defineProps({
  count: { type: Number, default: 0 },
})
</script>

<style scoped>
.apb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-danger);
  color: var(--ys-accent-on);
  font-size: var(--ys-text-xs);
  line-height: 1;
  font-weight: var(--ys-weight-semibold);
}
</style>
