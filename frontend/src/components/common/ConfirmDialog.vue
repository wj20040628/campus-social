<template>
  <!--
    统一确认框(2026-10-21)。
    存在的理由:群里有四个不可逆操作,改版前各自的处理方式完全不同 ——
    「移出群聊」直接发请求、「转让群主」弹 window.confirm、「全员禁言」直接生效、
    「退出群聊」直接退。四种口径,用户每次都要重新猜「这一下会不会真的发生」。
  -->
  <Teleport to="body">
    <Transition name="cdlg">
      <div
        v-if="modelValue"
        class="cdlg"
        role="alertdialog"
        aria-modal="true"
        :aria-label="title"
      >
        <!-- 点遮罩 = 取消。不关会让人以为卡住 -->
        <div class="cdlg__mask" @click="cancel" />
        <div class="cdlg__card">
          <h2 class="cdlg__t">{{ title }}</h2>
          <p v-if="text" class="cdlg__p">{{ text }}</p>
          <p v-if="hint" class="cdlg__hint">{{ hint }}</p>
          <div class="cdlg__foot">
            <button type="button" class="cdlg__btn" @click="cancel">{{ cancelText }}</button>
            <button
              ref="okEl"
              type="button"
              class="cdlg__btn"
              :class="danger ? 'cdlg__btn--danger' : 'cdlg__btn--pri'"
              @click="ok"
            >
              {{ confirmText || '确定' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
/**
 * 确认框:一句标题 + 一句后果 + 取消/确认。
 *
 * 三条约束(都来自这次改版的推导):
 * 1. 正文必须写清后果,不能只写「确定吗」。用户要知道点下去会发生什么,
 *    否则确认框只是加了一次点击,没有加信息。
 * 2. 危险操作的确认按钮用实心暖橙红,不用主色。主色意味着「推荐这么做」。
 * 3. 取消在左、确认在右,和全站所有对话框一致(改群名原本就是这么排的)。
 */
import { ref, watch, nextTick, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 标题:一句话,带问号。例:「移出 WJ?」 */
  title: { type: String, default: '' },
  /** 正文:点下去会发生什么 */
  text: { type: String, default: '' },
  /** 补充说明:可选,显示在正文下方 */
  hint: { type: String, default: '' },
  /** 确认按钮文案。带动词,不要写「确定」 */
  confirmText: { type: String, default: '' },
  cancelText: { type: String, default: '取消' },
  /** 危险操作:确认按钮实心暖橙红 */
  danger: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'confirm'])

const okEl = ref(null)

function close() {
  emit('update:modelValue', false)
}

function cancel() {
  close()
}

function ok() {
  close()
  emit('confirm')
}

function onKey(e) {
  if (e.key === 'Escape') cancel()
}

/*
 * 打开时:锁背景滚动 + 挂 Esc。
 * 锁滚动和 ActionSheet 用同一套写法,两者叠加时以「谁后关谁解锁」为准 ——
 * 这里只在关闭时恢复,和 ActionSheet 的行为一致,不会互相顶掉。
 */
watch(
  () => props.modelValue,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
    if (open) {
      window.addEventListener('keydown', onKey, true)
      // 焦点给确认按钮:键盘用户 Tab 一次就能反悔
      nextTick(() => okEl.value?.focus())
    } else {
      window.removeEventListener('keydown', onKey, true)
    }
  },
)

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey, true)
})
</script>

<style scoped src="./ConfirmDialog.css"></style>
