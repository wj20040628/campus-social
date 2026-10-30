<template>
  <div class="af">
    <div class="af__tabs" role="tablist" aria-label="文件审核状态">
      <button
        v-for="t in TABS"
        :key="String(t.value)"
        type="button"
        role="tab"
        class="af__tab"
        :class="{ 'is-active': status === t.value }"
        :aria-selected="status === t.value"
        @click="switchStatus(t.value)"
      >
        {{ t.label }}
      </button>
    </div>

    <div class="af__tools">
      <input
        v-model="keyword"
        class="af__search"
        type="search"
        maxlength="40"
        placeholder="搜标题或文件名"
        aria-label="搜文件"
        @keyup.enter="reload"
      />
      <button type="button" class="af__refresh" :disabled="loading" @click="reload">
        {{ loading ? '加载中' : '刷新' }}
      </button>
    </div>

    <div v-if="list.length" class="af__list">
      <button
        v-for="f in list"
        :key="f.id"
        type="button"
        class="af__row"
        @click="open(f)"
      >
        <span class="af__type">{{ fileExtText(f.ext) }}</span>
        <span class="af__main">
          <span class="af__title">{{ f.title }}</span>
          <span class="af__meta">
            {{ fileCategoryLabel(f.category) }} · {{ formatBytes(f.sizeBytes) }} ·
            {{ f.uploaderNickname }} · {{ formatTime(f.createTime) }}
          </span>
        </span>
        <FileStatusTag :status="f.status" />
      </button>
    </div>

    <StateBlock
      v-else-if="loaded && !loading"
      variant="empty"
      :title="emptyTitle"
      description="这里会随上传实时出现新文件"
      action-label="刷新"
      :icon="FolderOpened"
      @action="reload"
    />

    <div v-if="hasMore" class="af__tail">
      <button type="button" class="af__more" :disabled="loading" @click="loadMore">
        {{ loading ? '加载中' : '加载更多' }}
      </button>
    </div>

    <el-drawer v-model="drawerOpen" direction="btt" size="86%" :with-header="false">
      <div v-if="selected" class="afd">
        <div class="afd__bar">
          <button type="button" class="afd__close" @click="drawerOpen = false">关闭</button>
          <span class="afd__bar-t">文件审核</span>
          <button type="button" class="afd__download" @click="download">下载查看</button>
        </div>

        <div class="afd__head">
          <span class="afd__type">{{ fileExtText(selected.ext) }}</span>
          <div class="afd__headtext">
            <h2 class="afd__title">{{ selected.title }}</h2>
            <p class="afd__meta">
              {{ fileCategoryLabel(selected.category) }} · {{ formatBytes(selected.sizeBytes) }}
            </p>
          </div>
          <FileStatusTag :status="selected.status" />
        </div>

        <p class="afd__fname">原文件名：{{ selected.fileName }}</p>
        <p class="afd__uploader">上传者：{{ selected.uploaderNickname }}</p>
        <p class="afd__time">上传时间：{{ formatTime(selected.createTime) }}</p>
        <p v-if="selected.description" class="afd__desc">{{ selected.description }}</p>
        <p v-if="selected.rejectReason" class="afd__reason">理由：{{ selected.rejectReason }}</p>

        <div class="afd__actions">
          <template v-if="selected.status === 0">
            <button type="button" class="afd__approve" :disabled="acting" @click="approve">
              通过审核
            </button>
            <button type="button" class="afd__reject" :disabled="acting" @click="reject">
              驳回
            </button>
          </template>
          <button
            v-else-if="selected.status === 1"
            type="button"
            class="afd__reject"
            :disabled="acting"
            @click="takedown"
          >
            下架文件
          </button>
          <p v-else class="afd__done">这条已处理，无需重复操作</p>
        </div>
      </div>
    </el-drawer>

    <!-- 驳回 / 下架理由：常用理由点选 + 自由编辑（替代原来的空 prompt） -->
    <ReasonSheet
      v-model="reasonOpen"
      :title="reasonMode === 'reject' ? '驳回文件' : '下架文件'"
      :tip="reasonMode === 'reject'
        ? '驳回后文件不会公开，上传者会收到通知和这条理由。'
        : '下架后文件会从文件库和分享页消失，上传者会收到通知。'"
      :presets="reasonMode === 'reject' ? REJECT_PRESETS : TAKEDOWN_PRESETS"
      :confirm-text="reasonMode === 'reject' ? '驳回' : '下架'"
      @confirm="submitReason"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { FolderOpened } from '@element-plus/icons-vue'
import ReasonSheet from '@/components/common/ReasonSheet.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import FileStatusTag from '@/components/files/FileStatusTag.vue'
import * as fileApi from '@/api/files'
import {
  fileCategoryLabel,
  fileExtText,
  formatBytes,
} from '@/constants/fileCategories'
import { useAdminPending } from '@/composables/useAdminPending'

const TABS = [
  { value: 0, label: '待审核' },
  { value: 1, label: '已通过' },
  { value: 2, label: '未通过' },
  { value: 3, label: '已下架' },
  { value: null, label: '全部' },
]

const pending = useAdminPending()

const status = ref(0)
const keyword = ref('')
const list = ref([])
const page = ref(1)
const hasMore = ref(false)
const loading = ref(false)
const loaded = ref(false)
const selected = ref(null)
const drawerOpen = ref(false)
const acting = ref(false)
/** 理由面板：reject=驳回（待审）/ takedown=下架（已通过） */
const reasonOpen = ref(false)
const reasonMode = ref('reject')

const REJECT_PRESETS = [
  '文件里含个人隐私信息，请去掉后再传',
  '文件内容与标题或分类不符',
  '文件打不开或已损坏，请重新上传',
  '疑似他人作品，请上传原创或取得授权的内容',
  '内容不适合收录，请调整后重试',
]

const TAKEDOWN_PRESETS = [
  '举报成立，内容违规',
  '含侵权内容',
  '含他人隐私信息',
  '管理员复核后不适宜公开展示',
]

const emptyTitle = computed(() =>
  status.value === 0 ? '没有待审核的文件' : '这个状态下还没有文件'
)

async function load(reset = false) {
  if (reset) {
    page.value = 1
    list.value = []
    hasMore.value = false
  }
  loading.value = true
  try {
    const data = await fileApi.adminList({
      status: status.value,
      keyword: keyword.value.trim() || undefined,
      page: page.value,
      size: 20,
    })
    const items = data?.list ?? []
    list.value = reset ? items : [...list.value, ...items]
    hasMore.value = data?.hasMore === true
  } catch {
    if (!list.value.length) hasMore.value = false
  } finally {
    loading.value = false
    loaded.value = true
  }
}

function reload() {
  load(true)
}

function loadMore() {
  if (!hasMore.value || loading.value) return
  page.value += 1
  load(false)
}

function switchStatus(value) {
  if (status.value === value) return
  status.value = value
  load(true)
}

function open(file) {
  selected.value = { ...file }
  drawerOpen.value = true
}

async function refreshAfterAction() {
  drawerOpen.value = false
  await load(true)
  pending.refresh(true)
}

async function approve() {
  try {
    await ElMessageBox.confirm(
      '通过后文件会立即出现在文件库，上传者会收到通知并增加 1 分信誉分。',
      '通过审核',
      { confirmButtonText: '通过', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  acting.value = true
  try {
    await fileApi.adminReview(selected.value.id, 'approve')
    ElMessage.success('已通过')
    await refreshAfterAction()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    acting.value = false
  }
}

async function reject() {
  reasonMode.value = 'reject'
  reasonOpen.value = true
}

async function takedown() {
  reasonMode.value = 'takedown'
  reasonOpen.value = true
}

/**
 * 两个动作共用一张理由面板，差别只在调哪个接口。
 * 先关面板再请求：面板盖着 Loading 状态，管理员看不到「已驳回」的反馈。
 */
async function submitReason(reason) {
  const mode = reasonMode.value
  acting.value = true
  try {
    if (mode === 'reject') {
      await fileApi.adminReview(selected.value.id, 'reject', reason)
      ElMessage.success('已驳回')
    } else {
      await fileApi.adminTakedown(selected.value.id, reason)
      ElMessage.success('已下架')
    }
    await refreshAfterAction()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    acting.value = false
  }
}

async function download() {
  try {
    const blob = await fileApi.adminDownloadBlob(selected.value.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = selected.value.fileName || `file.${selected.value.ext}`
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(e?.message || '下载失败')
  }
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return String(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  load(true)
  pending.refresh()
})
</script>

<style scoped src="./AdminFiles.css"></style>
