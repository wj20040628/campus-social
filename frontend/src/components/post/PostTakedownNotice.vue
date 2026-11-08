<template>
  <section class="ptn">
    <p class="ptn__title">这篇帖子已被下架</p>
    <p v-if="info?.reason" class="ptn__row">
      <span class="ptn__label">下架原因</span>
      <span class="ptn__text">{{ info.reason }}</span>
    </p>
    <p v-if="info?.reviewerName" class="ptn__row">
      <span class="ptn__label">处理人</span>
      <span class="ptn__text">
        {{ info.reviewerName }}<template v-if="info.reviewerRole">（{{ info.reviewerRole }}）</template>
      </span>
    </p>
    <p v-if="info?.reviewedAt" class="ptn__row">
      <span class="ptn__label">处理时间</span>
      <span class="ptn__text">{{ fmt(info.reviewedAt) }}</span>
    </p>
    <p v-if="loading" class="ptn__muted">正在读取下架说明…</p>
    <p v-else-if="!info" class="ptn__muted">下架说明未找到，可在通知里查看处理结果</p>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import dayjs from 'dayjs'
import { reviewOfPost } from '@/api/review'

const props = defineProps({
  post: { type: Object, required: true },
})

const info = ref(null)
const loading = ref(false)

async function load() {
  info.value = null
  const id = props.post?.id
  if (!id) return
  loading.value = true
  try {
    info.value = await reviewOfPost(id)
  } catch {
    info.value = null
  } finally {
    loading.value = false
  }
}

function fmt(t) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
}

watch(() => props.post?.id, load, { immediate: true })
</script>

<style scoped>
.ptn {
  margin: var(--ys-space-3) var(--ys-space-4) 0;
  padding: var(--ys-space-3);
  border: 1px solid var(--ys-danger);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-danger-soft);
}

.ptn__title {
  margin: 0 0 var(--ys-space-2);
  color: var(--ys-danger);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
}

.ptn__row {
  display: flex;
  gap: var(--ys-space-2);
  margin: var(--ys-space-1) 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
}

.ptn__label {
  flex: none;
  color: var(--ys-muted);
}

.ptn__text {
  color: var(--ys-fg);
}

.ptn__muted {
  margin: var(--ys-space-1) 0 0;
  color: var(--ys-muted);
  font-size: var(--ys-text-xs);
}
</style>
