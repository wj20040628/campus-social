<template>
  <el-dialog
    :model-value="modelValue"
    title="下架帖子"
    width="480px"
    :fullscreen="isMobile"
    append-to-body
    align-center
    :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting"
    :show-close="!submitting"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <p v-if="excerpt" class="tdr__excerpt">「{{ excerpt }}」</p>
    <p class="tdr__label">下架原因（作者会看到）</p>
    <div class="tdr__presets">
      <button
        v-for="p in CIRCLE_REVIEW_REASONS"
        :key="p"
        type="button"
        class="tdr__preset"
        :class="{ 'is-active': reason === p }"
        @click="reason = p"
      >{{ p }}</button>
    </div>
    <el-input
      v-model="reason"
      type="textarea"
      :rows="3"
      :maxlength="512"
      show-word-limit
      placeholder="也可以补充更具体的原因"
    />

    <template #footer>
      <el-button :disabled="submitting" @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="danger" :loading="submitting" :disabled="!reason.trim()" @click="submit">
        确认下架
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useIsMobile } from '@/composables/useBreakpoint'
import { CIRCLE_REVIEW_REASONS } from '@/constants/circleReviewReasons'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  post: { type: Object, default: null },
  submitting: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const isMobile = useIsMobile()
const reason = ref('')

const excerpt = computed(() => {
  const text = String(props.post?.content || '').trim()
  return text.length > 30 ? `${text.slice(0, 30)}…` : text
})

watch(
  () => props.modelValue,
  (open) => {
    if (open) reason.value = ''
  },
)
watch(() => props.post?.id, () => { reason.value = '' })

function submit() {
  const text = reason.value.trim()
  if (!text || props.submitting) return
  emit('confirm', text)
}
</script>

<style scoped>
.tdr__excerpt {
  margin: 0 0 var(--ys-space-3);
  padding: var(--ys-space-2);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface-2);
  color: var(--ys-muted);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
}

.tdr__label {
  margin: 0 0 var(--ys-space-2);
  color: var(--ys-fg);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
}

.tdr__presets {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-3);
}

.tdr__preset {
  min-height: 32px;
  padding: 0 var(--ys-space-2);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface);
  color: var(--ys-fg);
  font-size: var(--ys-text-xs);
  cursor: pointer;
}

.tdr__preset:hover {
  border-color: var(--ys-accent);
  color: var(--ys-accent);
}

.tdr__preset.is-active {
  border-color: var(--ys-accent);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}
</style>
