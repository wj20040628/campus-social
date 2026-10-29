<template>
  <span class="fstatus" :data-status="status">{{ fileStatusLabel(status) }}</span>
</template>

<script setup>
import { fileStatusLabel } from '@/constants/fileCategories'

defineProps({
  status: { type: Number, default: 0 },
})
</script>

<style scoped>
.fstatus {
  flex: none;
  padding: 1px 8px;
  border-radius: var(--ys-radius-full);
  font-size: var(--ys-text-xs);
  line-height: 18px;
  white-space: nowrap;
}
.fstatus[data-status='0'] {
  background: var(--ys-warning-soft, var(--ys-surface-2));
  color: var(--ys-warning);
}
.fstatus[data-status='1'] {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}
.fstatus[data-status='2'] {
  background: var(--ys-danger-soft, var(--ys-surface-2));
  color: var(--ys-danger);
}
.fstatus[data-status='3'] {
  background: var(--ys-surface-2);
  color: var(--ys-muted);
}
</style>
