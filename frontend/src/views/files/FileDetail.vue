<template>
  <div class="fd">
    <div v-if="loading" class="fd__skeleton" aria-hidden="true">
      <span class="fd__sk-badge" />
      <span class="fd__sk-line" />
      <span class="fd__sk-line fd__sk-line--short" />
    </div>

    <StateBlock
      v-else-if="loadFailed"
      variant="error"
      title="文件没加载出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load"
    />

    <template v-else-if="detail">
      <article class="fd__card">
        <header class="fd__head">
          <span class="fd__type" :data-ext="detail.ext">{{ fileExtText(detail.ext) }}</span>
          <div class="fd__headtext">
            <h1 class="fd__title">{{ detail.title }}</h1>
            <div class="fd__meta">
              <span class="fd__cat">{{ fileCategoryLabel(detail.category) }}</span>
              <span>{{ formatBytes(detail.sizeBytes) }}</span>
              <span class="fd__sep" aria-hidden="true">·</span>
              <span class="fd__fname">{{ detail.fileName }}</span>
            </div>
          </div>
          <FileStatusTag v-if="isOwner" :status="detail.status" />
        </header>

        <p v-if="detail.description" class="fd__desc">{{ detail.description }}</p>

        <div class="fd__actions">
          <button
            type="button"
            class="fd__download"
            :disabled="!canDownload"
            @click="download"
          >
            下载文件
          </button>
          <button
            v-if="canDownload && canPreview"
            type="button"
            class="fd__iconbtn"
            aria-label="预览文件"
            title="在线预览"
            @click="previewOpen = true"
          >
            <el-icon :size="16"><View /></el-icon>
            <span>预览</span>
          </button>
          <button
            v-if="canInteract"
            type="button"
            class="fd__iconbtn"
            :class="{ 'is-on': detail.myLike }"
            :aria-pressed="detail.myLike"
            :aria-label="detail.myLike ? '取消点赞' : '点赞'"
            @click="toggleLike"
          >
            <ThumbUp :size="16" :filled="!!detail.myLike" />
            <span class="ys-num">{{ detail.likeCount }}</span>
          </button>
          <button
            v-if="canInteract"
            type="button"
            class="fd__iconbtn"
            :class="{ 'is-on': detail.myFavorite }"
            :aria-pressed="detail.myFavorite"
            :aria-label="detail.myFavorite ? '取消收藏' : '收藏'"
            @click="toggleFavorite"
          >
            <el-icon :size="16"><StarFilled v-if="detail.myFavorite" /><Star v-else /></el-icon>
            <span class="ys-num">{{ detail.favoriteCount }}</span>
          </button>
          <button v-if="canInteract" type="button" class="fd__iconbtn" aria-label="分享文件" @click="shareOpen = true">
            <el-icon :size="18"><Share /></el-icon>
          </button>
        </div>

        <p v-if="canDownload && !canPreview" class="fd__noop">
          {{ fileExtText(detail.ext) }} 暂不支持在线预览，下载后用 WPS / Office 打开
        </p>

        <div class="fd__stats">
          <span><b class="ys-num">{{ detail.viewCount }}</b> 浏览</span>
          <span><b class="ys-num">{{ detail.downloadCount }}</b> 下载</span>
          <span><b class="ys-num">{{ detail.likeCount }}</b> 点赞</span>
          <span><b class="ys-num">{{ detail.favoriteCount }}</b> 收藏</span>
          <span class="fd__time">{{ formatTime(detail.createTime) }}</span>
        </div>
      </article>

      <section class="fd__uploader">
        <el-avatar :size="42" :src="resolveThumb(detail.uploaderAvatar)">
          <span v-if="!detail.uploaderAvatar">{{ initial }}</span>
        </el-avatar>
        <div class="fd__who">
          <div class="fd__whorow">
            <span class="fd__name">{{ detail.uploaderNickname }}</span>
          </div>
          <span class="fd__role">上传者</span>
        </div>
      </section>

      <section v-if="isOwner" class="fd__owner">
        <p class="fd__owner-t">{{ ownerStatusText }}</p>
        <p v-if="detail.rejectReason" class="fd__owner-r">理由：{{ detail.rejectReason }}</p>
        <button type="button" class="fd__delete" :disabled="deleting" @click="remove">
          {{ deleting ? '删除中…' : '删除这份文件' }}
        </button>
      </section>

      <button
        v-if="!isOwner && detail.status === 1"
        type="button"
        class="fd__report"
        @click="reportOpen = true"
      >
        举报这份文件
      </button>
    </template>

    <ReportSheet
      v-model="reportOpen"
      :target-type="7"
      :target-id="detail?.id"
      sheet-title="举报文件"
      :submit-fn="submitReport"
    />

    <FilePreview
      v-model:open="previewOpen"
      :file="detail"
      @download="download"
    />

    <!-- 分享：卡片（文件名/大小/类型 + 二维码）+ 复制链接，替代原来的 window.prompt -->
    <ShareCardSheet
      v-model:open="shareOpen"
      :spec="shareSpec"
      :link="shareLink"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { WarningFilled, Star, StarFilled, Share, View } from '@element-plus/icons-vue'
import ThumbUp from '@/icons/ThumbUp.vue'
import FilePreview from '@/components/files/FilePreview.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import FileStatusTag from '@/components/files/FileStatusTag.vue'
import ReportSheet from '@/components/post/ReportSheet.vue'
import ShareCardSheet from '@/components/share/ShareCardSheet.vue'
import * as fileApi from '@/api/files'
import { submitReport } from '@/api/review'
import {
  fileCategoryLabel,
  fileExtText,
  formatBytes,
  isPreviewable,
} from '@/constants/fileCategories'
import { useUserStore } from '@/stores/user'
import { resolveThumb } from '@/utils/resolve'
import { shareUrl } from '@/utils/shareCard'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const detail = ref(null)
const loading = ref(true)
const loadFailed = ref(false)
const deleting = ref(false)
const reportOpen = ref(false)
const previewOpen = ref(false)
const shareOpen = ref(false)

const shareLink = computed(() => (detail.value ? shareUrl(`/f/${detail.value.id}`) : ''))
/**
 * 分享卡片内容：卡片上要能认出「这是什么文件」——
 * 左侧类型徽标（DOCX）、中间标题、副行是大小 + 简介。
 * 分类不进卡片（shareCard 的 topics 会加 # 前缀，那是给帖子话题用的）。
 */
const shareSpec = computed(() => {
  const d = detail.value
  if (!d) return null
  const sub = [formatBytes(d.sizeBytes), d.description || '']
  return {
    kind: 'file',
    badge: fileExtText(d.ext),
    authorName: d.uploaderNickname ? `${d.uploaderNickname} 分享` : '友声文件库',
    title: d.title,
    excerpt: sub.filter(Boolean).join(' · '),
    image: null,
    qrText: shareLink.value,
    meta: '扫码打开，手机可直接下载',
    fileName: `yousheng-file-${d.id}.png`,
  }
})

const isOwner = computed(
  () => detail.value?.canDelete === true || detail.value?.uploaderId === userStore.userInfo?.id
)
const canInteract = computed(() => detail.value?.status === 1)
const canDownload = computed(
  () => detail.value && (detail.value.status === 1 || isOwner.value)
)
const canPreview = computed(() => isPreviewable(detail.value?.ext))
const initial = computed(() => String(detail.value?.uploaderNickname || '友').slice(0, 1))
const ownerStatusText = computed(() => {
  if (!detail.value) return ''
  switch (detail.value.status) {
    case 0: return '审核中，通过后会出现在文件库'
    case 1: return '已通过审核，同学可以下载了'
    case 2: return '这份文件未通过审核'
    case 3: return '这份文件已被下架'
    default: return ''
  }
})

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    detail.value = await fileApi.detail(route.params.id)
  } catch {
    detail.value = null
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function download() {
  if (!canDownload.value) return
  const file = detail.value
  /*
   * 已过审文件走直链（游客身份也能下，OSS 直出，不占后端带宽）。
   * 待审/驳回/下架只有上传者能下，而 window.location 不带 Authorization，
   * 后端认不出作者会回 404 —— 这种情况必须 fetch 成 blob 再触发保存。
   */
  if (file.status === 1) {
    window.location.href = fileApi.downloadUrl(file.id)
    return
  }
  try {
    const blob = await fileApi.downloadBlob(file.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = file.fileName || `file.${file.ext || 'bin'}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 5000)
  } catch (e) {
    ElMessage.error(e?.message || '文件下载失败')
  }
}

async function toggleLike() {
  if (!canInteract.value) return
  const before = detail.value.myLike
  try {
    if (before) await fileApi.unlike(detail.value.id)
    else await fileApi.like(detail.value.id)
    detail.value.myLike = !before
    detail.value.likeCount = Math.max(0, Number(detail.value.likeCount || 0) + (before ? -1 : 1))
  } catch {
    // 全局 toast 已说明原因
  }
}

async function toggleFavorite() {
  if (!canInteract.value) return
  const before = detail.value.myFavorite
  try {
    if (before) await fileApi.unfavorite(detail.value.id)
    else await fileApi.favorite(detail.value.id)
    detail.value.myFavorite = !before
    detail.value.favoriteCount = Math.max(
      0,
      Number(detail.value.favoriteCount || 0) + (before ? -1 : 1)
    )
  } catch {
    // 全局 toast 已说明原因
  }
}

async function remove() {
  try {
    await ElMessageBox.confirm(
      '删除后文件会立即从文件库消失，这 1 分信誉加分也会失效，可能影响你的上传档位。',
      '删除文件',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    await fileApi.remove(detail.value.id)
    ElMessage.success('已删除')
    router.replace('/files?tab=mine')
  } catch {
    // 全局 toast 已说明原因
  } finally {
    deleting.value = false
  }
}

function formatTime(t) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
}

onMounted(load)
</script>

<style scoped src="./FileDetail.css"></style>
