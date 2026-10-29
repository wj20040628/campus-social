<template>
  <article
    class="fcard"
    role="button"
    tabindex="0"
    @click="$emit('open', file)"
    @keydown.enter.prevent="$emit('open', file)"
    @keydown.space.prevent="$emit('open', file)"
  >
    <span class="fcard__type" :data-ext="file.ext">{{ fileExtText(file.ext) }}</span>

    <div class="fcard__main">
      <div class="fcard__top">
        <h3 class="fcard__title">{{ file.title }}</h3>
        <FileStatusTag v-if="showStatus" :status="file.status" />
      </div>
      <div class="fcard__meta">
        <span class="fcard__cat">{{ fileCategoryLabel(file.category) }}</span>
        <span>{{ formatBytes(file.sizeBytes) }}</span>
        <span class="fcard__sep" aria-hidden="true">·</span>
        <span class="fcard__who">{{ file.uploaderNickname }}</span>
      </div>
      <div class="fcard__stats">
        <span>{{ file.downloadCount }} 次下载</span>
        <span>{{ file.likeCount }} 赞</span>
      </div>
    </div>

    <span class="fcard__chev" aria-hidden="true">›</span>
  </article>
</template>

<script setup>
import FileStatusTag from './FileStatusTag.vue'
import { fileCategoryLabel, fileExtText, formatBytes } from '@/constants/fileCategories'

defineProps({
  file: { type: Object, required: true },
  showStatus: { type: Boolean, default: false },
})

defineEmits(['open'])
</script>

<style scoped>
.fcard {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
  cursor: pointer;
  transition: border-color 0.18s ease, transform 0.18s ease;
}

.fcard:hover {
  border-color: var(--ys-accent);
}

.fcard:active {
  transform: scale(0.995);
}

.fcard:focus-visible {
  outline: 2px solid var(--ys-accent);
  outline-offset: 2px;
}

.fcard__type {
  flex: none;
  width: 48px;
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: 11px;
  font-weight: var(--ys-weight-semibold);
  letter-spacing: 0.02em;
}

.fcard__type[data-ext='pdf'] {
  background: rgba(229, 72, 77, 0.12);
  color: var(--ys-danger);
}

.fcard__type[data-ext='doc'],
.fcard__type[data-ext='docx'] {
  background: rgba(64, 124, 255, 0.12);
  color: #407cff;
}

.fcard__type[data-ext='ppt'],
.fcard__type[data-ext='pptx'] {
  background: rgba(230, 126, 34, 0.14);
  color: #d35400;
}

.fcard__type[data-ext='xls'],
.fcard__type[data-ext='xlsx'] {
  background: rgba(39, 174, 96, 0.13);
  color: #1e8449;
}

.fcard__main {
  flex: 1;
  min-width: 0;
}

.fcard__top {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.fcard__title {
  flex: 1;
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  word-break: break-word;
}

.fcard__meta {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-top: 5px;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
  min-width: 0;
}

.fcard__cat {
  flex: none;
  padding: 0 7px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  color: var(--ys-fg);
}

.fcard__who {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fcard__sep {
  flex: none;
}

.fcard__stats {
  display: flex;
  gap: var(--ys-space-3);
  margin-top: 4px;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fcard__chev {
  flex: none;
  color: var(--ys-meta);
  font-size: 20px;
  line-height: 1;
}
</style>
