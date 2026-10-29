<template>
  <div class="fs">
    <div v-if="loading" class="fs__loading">加载中…</div>

    <StateBlock
      v-else-if="loadFailed"
      variant="error"
      title="文件不存在或已下架"
      description="分享链接可能已失效，问问发你的同学"
      action-label="去友声看看"
      :icon="WarningFilled"
      @action="goHome"
    />

    <template v-else-if="file">
      <p class="fs__eyebrow">同学分享了一份文件</p>

      <article class="fs__card">
        <span class="fs__type" :data-ext="file.ext">{{ fileExtText(file.ext) }}</span>
        <div class="fs__headtext">
          <h1 class="fs__title">{{ file.title }}</h1>
          <div class="fs__meta">
            <span class="fs__cat">{{ fileCategoryLabel(file.category) }}</span>
            <span>{{ formatBytes(file.sizeBytes) }}</span>
          </div>
        </div>
      </article>

      <p v-if="file.description" class="fs__desc">{{ file.description }}</p>

      <div class="fs__actions">
        <button v-if="canPreview" type="button" class="fs__preview" @click="previewOpen = true">
          预览
        </button>
        <button type="button" class="fs__download" @click="download">下载文件</button>
      </div>

      <p v-if="!canPreview" class="fs__noop">
        {{ fileExtText(file.ext) }} 暂不支持在线预览，下载后用 WPS / Office 打开
      </p>

      <div class="fs__stats">
        <span><b class="ys-num">{{ file.downloadCount }}</b> 下载</span>
        <span><b class="ys-num">{{ file.likeCount }}</b> 赞</span>
        <span><b class="ys-num">{{ file.favoriteCount }}</b> 收藏</span>
      </div>

      <div class="fs__uploader">
        <el-avatar :size="40" :src="resolveThumb(file.uploaderAvatar)">
          <span v-if="!file.uploaderAvatar">{{ initial }}</span>
        </el-avatar>
        <div class="fs__who">
          <span class="fs__name">{{ file.uploaderNickname }}</span>
          <span class="fs__role">上传者{{ file.uploaderVerified ? ' · 已认证' : '' }}</span>
        </div>
      </div>

      <button type="button" class="fs__cta" @click="goLibrary">
        {{ logged ? '在文件库中查看' : '登录友声，发现更多资料' }}
      </button>
    </template>

    <FilePreview v-model:open="previewOpen" :file="file" @download="download" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import FilePreview from '@/components/files/FilePreview.vue'
import * as fileApi from '@/api/files'
import { fileCategoryLabel, fileExtText, formatBytes, isPreviewable } from '@/constants/fileCategories'
import { useUserStore } from '@/stores/user'
import { resolveThumb } from '@/utils/resolve'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const file = ref(null)
const loading = ref(true)
const loadFailed = ref(false)
const previewOpen = ref(false)
const logged = computed(() => userStore.hasToken)
const initial = computed(() => String(file.value?.uploaderNickname || '友').slice(0, 1))
const canPreview = computed(() => isPreviewable(file.value?.ext))

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    file.value = await fileApi.publicDetail(route.params.id)
  } catch {
    file.value = null
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function download() {
  window.location.href = fileApi.downloadUrl(file.value.id)
}

function goHome() {
  router.push(logged.value ? '/files' : '/login/form')
}

function goLibrary() {
  if (logged.value) {
    router.push(`/files/${file.value.id}`)
    return
  }
  router.push({ path: '/login/form', query: { returnUrl: route.fullPath } })
}

onMounted(load)
</script>

<style scoped>
.fs {
  max-width: 560px;
  margin: 0 auto;
  padding: var(--ys-space-5) var(--ys-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-3);
}

.fs__loading {
  padding: var(--ys-space-10) 0;
  text-align: center;
  color: var(--ys-muted);
  font-size: var(--ys-text-sm);
}

.fs__eyebrow {
  margin: 0;
  text-align: center;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fs__card {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
}

.fs__type {
  flex: none;
  width: 56px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-size: 12px;
  font-weight: var(--ys-weight-semibold);
}

.fs__type[data-ext='pdf'] {
  background: rgba(229, 72, 77, 0.12);
  color: var(--ys-danger);
}

.fs__headtext {
  min-width: 0;
}

.fs__title {
  margin: 0;
  font-size: var(--ys-text-xl);
  line-height: var(--ys-lh-xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  word-break: break-word;
}

.fs__meta {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-top: 6px;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fs__cat {
  padding: 0 7px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  color: var(--ys-fg);
}

.fs__desc {
  margin: 0;
  padding: 0 var(--ys-space-1);
  font-size: var(--ys-text-sm);
  line-height: 1.7;
  color: var(--ys-fg);
  white-space: pre-wrap;
  word-break: break-word;
}

.fs__actions {
  display: flex;
  align-items: stretch;
  gap: var(--ys-space-2);
}

.fs__preview {
  flex: none;
  min-height: 48px;
  padding: 0 var(--ys-space-5);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  color: var(--ys-fg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
}

.fs__download {
  flex: 1;
  min-height: 48px;
  border: none;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-semibold);
  cursor: pointer;
}

.fs__noop {
  margin: calc(var(--ys-space-3) * -1) 0 0;
  text-align: center;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fs__stats {
  display: flex;
  justify-content: center;
  gap: var(--ys-space-5);
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fs__stats b {
  color: var(--ys-fg);
}

.fs__uploader {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
  background: var(--ys-surface);
}

.fs__who {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.fs__name {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.fs__role {
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.fs__cta {
  min-height: var(--ys-touch-min);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  color: var(--ys-accent);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  cursor: pointer;
}

@media (min-width: 1024px) {
  .fs {
    padding: var(--ys-space-8) var(--ys-space-4);
  }
}
</style>
