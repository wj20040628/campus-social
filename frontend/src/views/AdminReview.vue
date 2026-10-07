<template>
  <div class="review-admin">
    <div class="admin-inner">
      <el-page-header @back="router.back()" title="内容审核" />

      <el-tabs v-model="tab" @tab-change="load">
        <el-tab-pane :label="`待审 (${pendingCount})`" name="pending" />
        <el-tab-pane label="已处理" name="processed" />
      </el-tabs>

      <div class="task-list">
        <el-card v-for="t in list" :key="t.id" class="task-card" :body-style="{ padding: '14px' }">
          <div class="task-head">
            <el-avatar :size="36" :src="resolveUrl(t.author?.avatarUrl)" />
            <div class="task-author">
              <div>{{ t.author ? displayName(t.author) : '未知用户' }}</div>
              <div class="task-time">{{ t.createTime }}</div>
            </div>
            <el-tag :type="statusTag(t.status)" size="small">{{ statusText(t.status) }}</el-tag>
          </div>

          <div class="task-content">{{ t.content }}</div>
          <div v-if="t.media" class="task-media">
            <span class="media-badge">含媒体</span>
          </div>
          <div v-if="t.hitWords?.length" class="task-hitwords">
            <span class="hitwords-label">命中词:</span>
            <el-tag
              v-for="w in t.hitWords"
              :key="w"
              type="danger"
              size="small"
              effect="plain"
            >
              {{ w }}
            </el-tag>
          </div>

          <!-- AI 机审预判(有 ai_verdict 才显示;人审可覆盖) -->
          <div v-if="t.aiVerdict" class="task-ai">
            <div class="ai-line">
              <el-tag :type="aiVerdictTag(t.aiVerdict)" size="small" effect="plain">AI 机审:{{ aiVerdictText(t.aiVerdict) }}</el-tag>
              <span v-if="t.aiReviewedAt" class="ai-time">{{ t.aiReviewedAt }}</span>
            </div>
            <div v-if="t.aiReason" class="ai-reason">AI 理由:{{ t.aiReason }}</div>
          </div>

          <div v-if="t.status === 0" class="task-actions">
            <el-input
              v-if="showReason[t.id]"
              v-model="reasons[t.id]"
              placeholder="驳回理由(可选)"
              maxlength="512"
              style="width: 240px"
            />
            <div class="task-btns">
              <el-button type="success" size="small" :loading="busy[t.id]" @click="doApprove(t)">
                通过
              </el-button>
              <el-button type="danger" size="plain" @click="showReason[t.id] = !showReason[t.id]">
                驳回
              </el-button>
            </div>
            <el-button
              v-if="showReason[t.id]"
              type="danger"
              size="small"
              :loading="busy[t.id]"
              @click="doReject(t)"
            >
              确认驳回
            </el-button>
          </div>
          <div v-else-if="t.status === 2 && t.reason" class="task-reason">
            驳回理由:{{ t.reason }}
          </div>
        </el-card>

        <el-empty v-if="list.length === 0" :description="tab === 'pending' ? '没有待审内容' : '暂无记录'" />
      </div>

      <div class="sentinel">
        <el-text v-if="loading" type="info">加载中…</el-text>
        <el-text v-else-if="!hasMore && list.length" type="info">到底了</el-text>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { pending as pendingApi, processed as processedApi, approve as approveApi, reject as rejectApi } from '@/api/review'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

const router = useRouter()
const tab = ref('pending')
const list = ref([])
const pendingCount = ref(0)
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
const busy = reactive({})
const reasons = reactive({})
const showReason = reactive({})
let observer = null

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

function statusText(s) {
  return ['待审', '已通过', '已驳回'][s] || '未知'
}
function statusTag(s) {
  return ['warning', 'success', 'danger'][s] || 'info'
}

/** AI 机审结论展示 */
function aiVerdictText(v) {
  return { approve: '建议通过', reject: '建议驳回', manual: '转人工' }[v] || '未知'
}
function aiVerdictTag(v) {
  return { approve: 'success', reject: 'danger', manual: 'warning' }[v] || 'info'
}

async function load(reset = false) {
  if (reset) {
    cursor.value = null
    list.value = []
    hasMore.value = true
  }
  loading.value = true
  try {
    const data = tab.value === 'pending'
      ? await pendingApi(cursor.value, 20)
      : await processedApi(cursor.value, 20)
    if (reset) list.value = data.list
    else list.value = [...list.value, ...data.list]
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
    if (tab.value === 'pending') {
      pendingCount.value = data.list.length + (cursor.value ? '…' : '')
    }
  } finally {
    loading.value = false
    nextTick(setupObserver)
  }
}

function setupObserver() {
  observer?.disconnect()
  const el = document.querySelector('.sentinel')
  if (!el) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load()
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(el)
}

async function doApprove(t) {
  try {
    await ElMessageBox.confirm(`确认通过帖子 ${t.postId}?`, '审核', { type: 'warning' })
  } catch {
    return
  }
  busy[t.id] = true
  try {
    await approveApi(t.id)
    ElMessage.success('已通过')
    load(true)
  } finally {
    busy[t.id] = false
  }
}

async function doReject(t) {
  busy[t.id] = true
  try {
    await rejectApi(t.id, reasons[t.id] || '')
    ElMessage.success('已驳回')
    load(true)
  } finally {
    busy[t.id] = false
  }
}

onMounted(() => load(true))
onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.admin-inner {
  max-width: 760px;
  margin: 0 auto;
  padding: 20px;
}
.admin-inner .task-card {
  border-radius: var(--card-radius);
  transition: box-shadow 0.2s;
}
.admin-inner .task-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}
.task-list {
  padding: 8px 0;
}
.task-card {
  margin-bottom: 12px;
}
.task-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.task-author {
  flex: 1;
}
.task-author div:first-child {
  font-weight: 600;
}
.task-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.task-content {
  margin: 12px 0;
  white-space: pre-wrap;
  line-height: 1.6;
}
.task-media {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.media-badge {
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  padding: 2px 8px;
  border-radius: 4px;
}
.task-actions {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.task-btns {
  display: flex;
  gap: 6px;
}
.task-reason {
  margin-top: 10px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.task-hitwords {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: var(--el-color-danger-light-9);
  border-radius: 6px;
}
.hitwords-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* AI 机审预判区 */
.task-ai {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  background: var(--el-color-primary-light-9);
  border-radius: 6px;
}
.ai-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ai-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ai-reason {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
