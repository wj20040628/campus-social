<template>
  <div v-if="quota" class="fquota">
    <div class="fquota__head">
      <span class="fquota__label">文件容量</span>
      <span class="fquota__nums ys-num">
        {{ formatBytes(quota.usedBytes) }} / {{ formatBytes(quota.totalLimitBytes) }}
      </span>
    </div>
    <div class="fquota__track" role="progressbar" :aria-valuenow="percent" aria-valuemin="0" aria-valuemax="100">
      <span class="fquota__fill" :style="{ width: `${percent}%` }" />
    </div>
    <p class="fquota__sub">
      <template v-if="quota.tier > 0">
        当前 {{ quota.tier }} 档（信誉分 {{ quota.score }}） · 单文件 ≤ {{ formatBytes(quota.singleLimitBytes) }}
      </template>
      <template v-else>未获得上传额度</template>
    </p>
    <p v-if="!quota.canUpload && quota.reason" class="fquota__warn">{{ quota.reason }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { formatBytes } from '@/constants/fileCategories'

const props = defineProps({
  quota: { type: Object, default: null },
})

const percent = computed(() => {
  const total = Number(props.quota?.totalLimitBytes) || 0
  if (!total) return 0
  const used = Number(props.quota?.usedBytes) || 0
  return Math.min(100, Math.round((used / total) * 100))
})
</script>

<style scoped>
.fquota {
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
}

.fquota__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--ys-space-3);
}

.fquota__label {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.fquota__nums {
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.fquota__track {
  height: 6px;
  margin-top: var(--ys-space-2);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  overflow: hidden;
}

.fquota__fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--ys-accent);
  transition: width 0.25s ease;
}

.fquota__sub {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.fquota__warn {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-danger);
}
</style>
