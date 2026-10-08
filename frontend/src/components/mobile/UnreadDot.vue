<template>
  <!--
    未读红点(icons-req.md §4.1)
    不是图标,是容器上叠加的元素:
      8px 圆点 + --ys-accent-2(暖橙红,与主色区分)
      位置:图标右上角,偏移 -2px,-2px
    必须带 aria-label —— 纯 CSS 圆点对读屏完全不可见。
    上限显示 99+。
  -->
  <span
    v-if="count > 0"
    class="unread-dot"
    :class="{ 'unread-dot--with-count': showCount }"
    role="status"
    :aria-label="label"
  >
    <span v-if="showCount" class="unread-dot__text ys-num">{{ display }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 未读数。<=0 时整个组件不渲染(不是渲染一个 0) */
  count: { type: Number, default: 0 },
  /** 是否显示数字。小红点形态(纯圆点)只给 aria-label 用 */
  showCount: { type: Boolean, default: false },
  /** 名称,用于播报,如「条未读」 */
  noun: { type: String, default: '条未读' },
})

/** 99+ 封顶 */
const display = computed(() => (props.count > 99 ? '99+' : String(props.count)))

const label = computed(() => `${props.count} ${props.noun}`)
</script>

<style scoped>
.unread-dot {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 8px;
  height: 8px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent-2);
  pointer-events: none;
}

.unread-dot--with-count {
  top: -6px;
  right: -10px;
  min-width: 18px;
  height: 18px;
  padding: 0 var(--ys-space-1);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 2px solid var(--ys-surface);
  box-sizing: content-box;
}

.unread-dot__text {
  font-size: var(--ys-text-xs);
  line-height: 1;
  color: var(--ys-accent-on);
  font-weight: var(--ys-weight-medium);
}
</style>