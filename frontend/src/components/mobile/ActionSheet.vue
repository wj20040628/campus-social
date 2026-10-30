<template>
  <Teleport to="body">
    <Transition name="asheet">
      <div
        v-if="modelValue"
        class="asheet"
        role="dialog"
        aria-modal="true"
        :aria-label="title || undefined"
        @keydown.esc="close"
      >
        <!-- 遮罩点击必须关闭 -->
        <div class="asheet__mask" @click="close" />
        <div class="asheet__panel" :style="panelStyle">
          <header v-if="title || $slots.header" class="asheet__head">
            <h2 class="asheet__title">{{ title }}</h2>
            <slot name="header" />
            <button
              type="button"
              class="asheet__close"
              aria-label="关闭"
              @click="close"
            >
              <el-icon :size="20"><Close /></el-icon>
            </button>
          </header>

          <!-- 面板内允许滚动:名单类内容可能很长 -->
          <div class="asheet__body">
            <slot />
          </div>

          <!--
            取消区:固定在最底部,与操作项之间 1px 分隔。
            「取消必须永远存在且永远在最下」是操作表能被安全使用的底线。
          -->
          <button type="button" class="asheet__cancel" @click="close">
            {{ cancelText }}
          </button>
          <SafeArea position="bottom" />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
/**
 * 底部操作表(mobile-shell.md §3)
 *
 * 三个用途:气泡长按菜单 / Feed「不感兴趣」/ 通用危险操作确认。
 *
 * 五条错误用法(mobile-shell.md §3.5,这里逐条守住):
 * - 不承载多步表单(要输入用全屏 Modal 或独立页)
 * - 超过 5 个项(超过就该用独立页面)
 * - 底部弹出多个 ActionSheet
 * - 点击遮罩不关闭      -> 已实现
 * - 面板内不能滚动      -> 已实现(.asheet__body 可滚)
 */
import { computed, watch, onBeforeUnmount } from 'vue'
import { Close } from '@element-plus/icons-vue'
import SafeArea from './SafeArea.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  /** 面板高度。70vh 用于「谁还没看」这类长名单 */
  height: { type: String, default: '' },
  /** 底部取消文案。默认「取消」 */
  cancelText: { type: String, default: '取消' },
})

const emit = defineEmits(['update:modelValue', 'close'])

const panelStyle = computed(() => (props.height ? { height: props.height } : {}))

function close() {
  emit('update:modelValue', false)
  emit('close')
}

// 打开时锁背景滚动,关闭时恢复。多个 ActionSheet 叠加会打架,故只锁一次。
watch(
  () => props.modelValue,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
  }
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
})
</script>

<style scoped>
.asheet {
  position: fixed;
  inset: 0;
  z-index: var(--ys-z-drawer);
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.asheet__mask {
  position: absolute;
  inset: 0;
  background: var(--ys-overlay);
}

.asheet__panel {
  position: relative;
  background: var(--ys-surface);
  /* 圆角仅顶部两角 —— 底部贴屏边 */
  border-radius: var(--ys-radius-2xl) var(--ys-radius-2xl) 0 0;
  display: flex;
  flex-direction: column;
  max-height: 82vh;
  box-shadow: var(--ys-shadow-3);
}

.asheet__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: 56px;
  padding: 0 var(--ys-space-2) 0 var(--ys-space-4);
  border-bottom: 1px solid var(--ys-border-soft);
}

.asheet__title {
  flex: 1;
  margin: 0;
  font-size: var(--ys-text-lg);
  font-weight: var(--ys-weight-semibold);
  letter-spacing: var(--ys-ls-lg);
  color: var(--ys-fg);
}

.asheet__close {
  /* 触控热区 44x44 */
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-muted);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.asheet__close:hover {
  opacity: 0.7;
}

.asheet__body {
  flex: 1;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}

/* 操作项:最小高 44px(AC-15) */
.asheet__item {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: none;
  background: transparent;
  font-size: var(--ys-text-base);
  font-family: var(--ys-font-body);
  color: var(--ys-fg);
  text-align: left;
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.asheet__item:hover {
  background: var(--ys-surface-2);
}

/*
 * 危险操作用辅色 --ys-accent-2(暖橙红),不用纯红。
 * 纯红读起来像「系统错误」,而「删除」「举报」是用户主动选择的动作。
 */
.asheet__item--danger {
  color: var(--ys-accent-2);
}

.asheet__cancel {
  min-height: var(--ys-touch-min);
  border: none;
  border-top: 1px solid var(--ys-border);
  background: var(--ys-surface);
  font-size: var(--ys-text-base);
  font-family: var(--ys-font-body);
  color: var(--ys-muted);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.asheet__cancel:hover {
  background: var(--ys-surface-2);
}

/* 出现 200ms / 消失 150ms,禁止弹跳 */
.asheet-enter-active .asheet__panel {
  transition: transform var(--ys-motion-slow) var(--ys-ease-in-out);
}

.asheet-leave-active .asheet__panel {
  transition: transform var(--ys-motion-fast) var(--ys-ease-in-out);
}

.asheet-enter-active .asheet__mask,
.asheet-leave-active .asheet__mask {
  transition: opacity var(--ys-motion-base) var(--ys-ease-out);
}

.asheet-enter-from .asheet__panel,
.asheet-leave-to .asheet__panel {
  transform: translateY(100%);
}

.asheet-enter-from .asheet__mask,
.asheet-leave-to .asheet__mask {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .asheet-enter-active .asheet__panel,
  .asheet-leave-active .asheet__panel,
  .asheet-enter-active .asheet__mask,
  .asheet-leave-active .asheet__mask {
    transition: none;
  }
}
</style>