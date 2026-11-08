<template>
  <ActionSheet v-model="open" title="管理这条帖子">
    <button type="button" class="cpm__item" @click="run('top')">
      {{ post?.isTop ? '取消置顶' : '置顶这条' }}
    </button>
    <button type="button" class="cpm__item" @click="run('takedown')">
      下架这条
    </button>
    <button type="button" class="cpm__item cpm__item--danger" @click="run('move')">
      移出该圈
    </button>
  </ActionSheet>

  <TakedownReasonDialog
    v-model="reasonOpen"
    :post="post"
    :submitting="submitting"
    @confirm="confirmTakedown"
  />
</template>

<script setup>
import { computed, ref } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import TakedownReasonDialog from './TakedownReasonDialog.vue'
import { useCircleAdminActions } from '@/composables/useCircleAdminActions'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  post: { type: Object, default: null },
  circleId: { type: [Number, String], required: true },
})
const emit = defineEmits(['update:modelValue', 'changed'])

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})
const reasonOpen = ref(false)
const submitting = ref(false)
const act = useCircleAdminActions(
  computed(() => props.circleId),
  async () => emit('changed'),
  null,
)

function run(kind) {
  const target = props.post
  open.value = false
  if (!target) return
  if (kind === 'top') act.top(target)
  else if (kind === 'move') act.move(target, null)
  else if (kind === 'takedown') reasonOpen.value = true
}

async function confirmTakedown(reason) {
  if (!props.post) return
  submitting.value = true
  try {
    const ok = await act.takedown(props.post, reason)
    if (ok) reasonOpen.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.cpm__item {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: none;
  background: transparent;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-fg);
  text-align: left;
  cursor: pointer;
}

.cpm__item:hover {
  background: var(--ys-surface-2);
}

.cpm__item--danger {
  color: var(--ys-accent-2);
}
</style>
