<template>
  <!--
    会话行左滑操作容器（2026-10-20）
    手指向左拖 -> 行左移 -> 右侧露出 免打扰 / 置顶 / 删除 三个按钮。

    [方向] 最初做成右滑（按钮在左），用户明确纠正为左滑。
    左滑是 iOS/微信/QQ 的一致习惯，用户的肌肉记忆是往左推；
    而且右滑在 Android 上常被系统当成「返回上一页」，会打架。

    三条边界：
      1) 只在触屏生效（pointerType === 'mouse'）：桌面上拖拽和文字选择打架。
         桌面走右键菜单（ChatRowMenu.vue）。
      2) 纵向手势直接放行：touch-action: pan-y + 首次位移超过 8px 时定方向，
         斜着滑不会把页面滚不动。
      3) 拖过 6px 就吞掉点击：否则松手会顺带把会话打开。
  -->
  <div class="swipe" :class="{ 'is-open': open }">
    <div class="swipe__acts" :aria-hidden="!open">
      <button
        type="button"
        class="swipe__act"
        :tabindex="open ? 0 : -1"
        @click="$emit('action', 'mute')"
      >{{ muted ? '取消免打扰' : '免打扰' }}</button>
      <button
        type="button"
        class="swipe__act swipe__act--pin"
        :tabindex="open ? 0 : -1"
        @click="$emit('action', 'pin')"
      >{{ pinned ? '取消置顶' : '置顶' }}</button>
      <button
        type="button"
        class="swipe__act swipe__act--danger"
        :tabindex="open ? 0 : -1"
        @click="$emit('action', 'delete')"
      >删除</button>
    </div>

    <div
      ref="bodyEl"
      class="swipe__body"
      :style="bodyStyle"
      @pointerdown="onDown"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
      @click.capture="onClickCapture"
    >
      <slot />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'

/** 三个按钮的宽度总和，和 .swipe__act 的 width 必须一致 */
const MAX = 216
/** 松手时超过这个比例就吸附到打开态 */
const SNAP = 0.35

const props = defineProps({
  open: { type: Boolean, default: false },
  pinned: { type: Boolean, default: false },
  muted: { type: Boolean, default: false },
})
const emit = defineEmits(['update:open', 'action'])

const bodyEl = ref(null)
/**
 * offset 记的是**已露出的宽度**（0 ~ MAX，恒为正），
 * 行本体的位移是它的相反数。这样吸附/夹取都按正数算，读起来不用来回换符号。
 */
const offset = ref(0)
const dragging = ref(false)

let startX = 0
let startY = 0
let base = 0
let axis = null
let moved = false

const bodyStyle = computed(() => ({
  transform: `translateX(${-offset.value}px)`,
  transition: dragging.value ? 'none' : 'transform var(--ys-motion-fast) var(--ys-ease-out)',
}))

watch(
  () => props.open,
  (v) => {
    if (!dragging.value) offset.value = v ? MAX : 0
  },
)

function onDown(e) {
  if (e.pointerType === 'mouse') return
  startX = e.clientX
  startY = e.clientY
  base = offset.value
  axis = null
  moved = false
  dragging.value = true
}

function onMove(e) {
  if (!dragging.value || e.pointerType === 'mouse') return
  const dx = e.clientX - startX
  const dy = e.clientY - startY
  if (!axis) {
    if (Math.abs(dx) > 8 && Math.abs(dx) > Math.abs(dy) * 1.2) {
      axis = 'x'
      bodyEl.value?.setPointerCapture?.(e.pointerId)
    } else if (Math.abs(dy) > 8) {
      axis = 'y'
      dragging.value = false
      offset.value = props.open ? MAX : 0
      return
    } else {
      return
    }
  }
  if (axis !== 'x') return
  moved = moved || Math.abs(dx) > 6
  // 只允许向左：向右拖不出现任何东西，避免和系统「返回」手势抢
  offset.value = Math.min(MAX, Math.max(0, base - dx))
}

function onUp() {
  if (!dragging.value) return
  dragging.value = false
  if (axis !== 'x') return
  const next = offset.value > MAX * SNAP
  offset.value = next ? MAX : 0
  emit('update:open', next)
}

/** 拖动过就把松手后的 click 吃掉，别顺带打开会话 */
function onClickCapture(e) {
  if (moved) {
    e.preventDefault()
    e.stopPropagation()
    moved = false
  }
}
</script>

<style scoped src="./ChatRowSwipe.css"></style>
