<template>
  <!--
    全屏看图（2026-10-28）。
    为什么不用 el-image 的 preview：那套自带工具栏、缩放、切换按钮，
    而这里只需要「点开看全图、点一下关掉」——多出来的控件在手机上
    正好压在图片上，反而挡内容。
  -->
  <Teleport to="body">
    <Transition name="iv">
      <div
        v-if="modelValue"
        class="iv"
        role="dialog"
        aria-modal="true"
        :aria-label="alt || '查看大图'"
        @click="close"
      >
        <img class="iv__img" :src="src" :alt="alt" />
        <button type="button" class="iv__close" aria-label="关闭" @click.stop="close">
          <el-icon :size="22"><Close /></el-icon>
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { watch, onBeforeUnmount } from 'vue'
import { Close } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  src: { type: String, default: '' },
  alt: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])

function close() {
  emit('update:modelValue', false)
}

function onKey(e) {
  if (e.key === 'Escape') close()
}

// 打开时锁背景滚动并挂 Esc；关闭/卸载都要还原，否则整页再也滚不动
watch(
  () => props.modelValue,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
    if (open) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
  }
)

onBeforeUnmount(() => {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
.iv {
  position: fixed;
  inset: 0;
  z-index: var(--ys-z-toast);
  display: grid;
  place-items: center;
  padding: var(--ys-space-4);
  background: rgba(0, 0, 0, 0.92);
  cursor: zoom-out;
}

.iv__img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: var(--ys-radius-md);
}

.iv__close {
  position: absolute;
  top: calc(var(--ys-space-3) + env(safe-area-inset-top, 0px));
  right: var(--ys-space-3);
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: var(--ys-radius-full);
  background: rgba(0, 0, 0, 0.35);
  color: #fff;
  cursor: pointer;
}

.iv-enter-active,
.iv-leave-active {
  transition: opacity var(--ys-motion-fast) var(--ys-ease-out);
}

.iv-enter-from,
.iv-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .iv-enter-active,
  .iv-leave-active {
    transition: none;
  }
}
</style>
