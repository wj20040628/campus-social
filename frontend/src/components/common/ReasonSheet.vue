<template>
  <!--
    驳回 / 下架理由（2026-10-28）。
    管理端此前用 ElMessageBox.prompt：一个空输入框，20 条审核要手打 20 遍
    「文件含个人信息」。这里给常用理由点选，选中后可继续改，
    确认按钮在没有内容时是禁用的——理由必填这件事不该等后端 400 才说。
  -->
  <ActionSheet
    :model-value="modelValue"
    :title="title"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="rs">
      <p v-if="tip" class="rs__tip">{{ tip }}</p>

      <div class="rs__presets">
        <button
          v-for="p in presets"
          :key="p"
          type="button"
          class="rs__preset"
          :class="{ 'is-on': text === p }"
          @click="text = p"
        >
          {{ p }}
        </button>
      </div>

      <label class="rs__field">
        <span class="rs__label">理由（对方会看到）</span>
        <textarea
          v-model="text"
          class="rs__input"
          rows="3"
          maxlength="200"
          :placeholder="placeholder"
        />
      </label>
      <p class="rs__count">{{ text.length }}/200</p>

      <div class="rs__btns">
        <button type="button" class="rs__btn" @click="close">取消</button>
        <button
          type="button"
          class="rs__btn rs__btn--danger"
          :disabled="!text.trim()"
          @click="submit"
        >
          {{ confirmText }}
        </button>
      </div>
    </div>
  </ActionSheet>
</template>

<script setup>
import { ref, watch } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '填写理由' },
  tip: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  confirmText: { type: String, default: '提交' },
  presets: { type: Array, default: () => [] },
  /** 打开时预填的内容（如按比对结果自动生成的建议理由） */
  defaultValue: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const text = ref('')

// 每次打开都重置：复用同一个面板时残留上次的理由会让人误以为已填好
watch(
  () => props.modelValue,
  (open) => {
    if (open) text.value = props.defaultValue || ''
  }
)

function close() {
  emit('update:modelValue', false)
}

function submit() {
  const value = text.value.trim()
  if (!value) return
  emit('confirm', value)
  close()
}
</script>

<style scoped>
.rs {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-4);
}

.rs__tip {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}

.rs__presets {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.rs__preset {
  min-height: 34px;
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  color: var(--ys-fg-2);
  cursor: pointer;
}

.rs__preset.is-on {
  border-color: var(--ys-accent);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}

.rs__field {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
}

.rs__label {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.rs__input {
  width: 100%;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  /* iOS Safari 下 <16px 聚焦会放大页面 */
  font-size: var(--ys-text-md);
  line-height: var(--ys-lh-md);
  color: var(--ys-fg);
  resize: vertical;
}

.rs__input:focus-visible {
  outline: 2px solid var(--ys-focus-ring);
  outline-offset: 1px;
}

.rs__count {
  margin: calc(var(--ys-space-2) * -1) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  text-align: right;
}

.rs__btns {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--ys-space-2);
}

.rs__btn {
  min-height: var(--ys-touch-min);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-fg);
  cursor: pointer;
}

/* 驳回/下架是用户主动选择的重动作，用辅色而不是纯红 */
.rs__btn--danger {
  border-color: var(--ys-accent-2);
  background: var(--ys-accent-2);
  color: #fff;
}

.rs__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
