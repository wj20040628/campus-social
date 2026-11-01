<template>
  <!-- 处罚类处置的必填理由（2026-10-31）：会原样进对方站内通知与申诉材料 -->
  <div class="rhn">
    <label class="rhn__label" for="rp-handle-note">处置理由（必填）</label>
    <textarea
      id="rp-handle-note"
      :value="modelValue"
      rows="2"
      maxlength="200"
      placeholder="写清楚违规点，例如：评论区多次发布广告引流"
      @input="emit('update:modelValue', $event.target.value)"
    />
    <p class="rhn__hint">{{ modelValue.length }}/200 · 理由会随通知发给被处置人</p>
  </div>
</template>

<script setup>
defineProps({
  modelValue: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue'])
</script>

<style scoped>
.rhn {
  display: grid;
  gap: var(--ys-space-1);
}

.rhn__label {
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2, var(--ys-fg));
}

.rhn textarea {
  min-height: 56px;
  padding: var(--ys-space-2) var(--ys-space-3);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface);
  color: var(--ys-fg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  resize: vertical;
}

.rhn__hint {
  margin: 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}
</style>
