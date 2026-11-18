<template>
  <div class="cc" :class="{ 'cc--soft': softActive }">
    <div
      ref="scrollEl"
      class="cc__scroll"
      :class="{ 'is-end': atEnd }"
      role="tablist"
      :aria-label="ariaLabel"
      @scroll="syncEnd"
    >
      <button
        v-for="o in options"
        :key="String(o.value)"
        type="button"
        role="tab"
        class="cc__chip"
        :class="{ 'is-on': isOn(o.value) }"
        :data-value="String(o.value)"
        :aria-selected="isOn(o.value)"
        @click="pick(o.value)"
      >
        {{ o.label }}
        <span v-if="o.count > 0" class="cc__n ys-num">{{ o.count }}</span>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * 分类 chip 条（2026-11-14）。
 *
 * 单行横滑、不折行，就是最初那一排胶囊；不加「更多」入口、箭头或折行。
 * 横向滚动交给浏览器原生 overflow，不在页面里接管手势：
 * 红米自带浏览器会把页面 JS 的横滑接管连同 touchmove 一起吃掉，
 * 但原生横向滚动在它关掉「左右滑动前进」时是正常的；之前加的
 * touch-action: pan-y 恰恰把原生横滚也关掉了，所以条子纹丝不动。
 * 现在只保留 overscroll-behavior-x: contain：滑到最右不再把这次手势
 * 交给浏览器的「前进/后退」。
 * 右侧 8px 渐隐提示还能往右滑，滑到最右取消渐隐。
 * 选中项可能在可视区外（换 Tab 重挂载、分类条数加载后 chip 变宽），
 * 挂载和选项变化时把它带回视野。
 * 浏览器前进/后退把页面从缓存里捞回来时不会重跑 onMounted，靠 pageshow 补一次。
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: null },
  options: { type: Array, default: () => [] },
  ariaLabel: { type: String, default: '分类筛选' },
  softActive: { type: Boolean, default: false },
})
const emit = defineEmits(['change'])

const scrollEl = ref(null)
const atEnd = ref(false)

function isOn(v) {
  return v === props.modelValue
}

function syncEnd() {
  const el = scrollEl.value
  if (!el) return
  atEnd.value = el.scrollLeft + el.clientWidth >= el.scrollWidth - 2
}

function revealActive() {
  const el = scrollEl.value
  const chip = el?.querySelector('.cc__chip.is-on')
  if (!el || !chip) return
  const cr = chip.getBoundingClientRect()
  const er = el.getBoundingClientRect()
  if (cr.left < er.left) el.scrollLeft += cr.left - er.left - 4
  else if (cr.right > er.right) el.scrollLeft += cr.right - er.right + 4
}

function pick(v) {
  if (!isOn(v)) emit('change', v)
}

function resync() {
  syncEnd()
  revealActive()
}

function onPageShow(e) {
  if (e.persisted) nextTick(resync)
}

watch(
  () => props.options,
  () => nextTick(resync)
)

watch(
  () => props.modelValue,
  () => nextTick(revealActive)
)

onMounted(() => {
  nextTick(resync)
  window.addEventListener('pageshow', onPageShow)
})

onBeforeUnmount(() => {
  window.removeEventListener('pageshow', onPageShow)
})
</script>

<style scoped src="./CategoryChips.css"></style>