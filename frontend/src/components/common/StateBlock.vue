<template>
  <!--
    通用状态区块:空态 / 错误 / 权限不足 三态合一。
    依据 SPEC §9 AC-19:任意列表为空时必须「一句人话 + 一个可点行动按钮」,
    不得出现空白页。因此本组件**强制**要求 actionLabel + action 之一,
    不给「只有文案没有出口」的用法留空间。
  -->
  <div class="state" :class="`state--${variant}`" role="status">
    <!-- 图标:纯装饰,状态本身由文字传达(不能只靠图标猜) -->
    <el-icon v-if="icon" :size="24" class="state__icon"><component :is="icon" /></el-icon>

    <p class="state__title">{{ title }}</p>
    <p v-if="description" class="state__desc">{{ description }}</p>

    <button
      v-if="actionLabel"
      type="button"
      class="state__action"
      :class="{ 'state__action--primary': primary }"
      @click="$emit('action')"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>

<script setup>
/**
 * @param {'empty'|'error'|'denied'} variant 三态
 * @param {string} title       主句,必须是真实中文,禁止「暂无数据」「敬请期待」
 * @param {string} description 副句,解释怎么办
 * @param {string} actionLabel 行动按钮文案(AC-19 硬要求,不给则不出按钮)
 * @param {boolean} primary    主按钮(实底)还是次按钮(描边)
 * @param {string|object} icon EP 图标组件
 */
defineProps({
  variant: { type: String, default: 'empty' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  actionLabel: { type: String, default: '' },
  primary: { type: Boolean, default: true },
  icon: { type: [String, Object], default: null },
})

defineEmits(['action'])
</script>

<style scoped>
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-10) var(--ys-space-4);
  text-align: center;
}

.state__icon {
  color: var(--ys-muted);
  margin-bottom: var(--ys-space-1);
}

.state__title {
  margin: 0;
  /* 主句用 2xl/600 —— 空态主句是页面里最重要的一句话 */
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  max-width: 20ch;
}

.state__desc {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  max-width: 32ch;
}

/* 错误态文案用 danger,权限不足用中性 —— 权限不足不是错误 */
.state--error .state__title {
  font-size: var(--ys-text-xl);
  line-height: var(--ys-lh-xl);
  letter-spacing: var(--ys-ls-xl);
}

.state--error .state__desc {
  color: var(--ys-danger);
}

.state--denied .state__title {
  font-size: var(--ys-text-xl);
  line-height: var(--ys-lh-xl);
  letter-spacing: var(--ys-ls-xl);
}

.state__action {
  min-height: var(--ys-touch-min);
  margin-top: var(--ys-space-2);
  padding: 0 var(--ys-space-5);
  border-radius: var(--ys-radius-lg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out),
    opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.state__action:active {
  opacity: 0.7;
}

.state__action--primary {
  border: 1px solid var(--ys-accent);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
}

.state__action--primary:hover {
  background: var(--ys-accent-hover);
  border-color: var(--ys-accent-hover);
}

.state__action:not(.state__action--primary) {
  border: 1px solid var(--ys-border-control);
  background: var(--ys-surface);
  color: var(--ys-fg-2);
}

.state__action:not(.state__action--primary):hover {
  background: var(--ys-surface-2);
}
</style>