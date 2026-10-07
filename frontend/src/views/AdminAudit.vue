<template>
  <div class="audit-admin">
    <div class="admin-inner">
      <el-page-header @back="router.back()" title="操作日志" />

      <!-- 筛选栏:动作 + 用户 + 关键字,可任意组合(都为空 = 全量) -->
      <div class="filter-bar">
        <el-select v-model="f.action" placeholder="全部动作" clearable style="width: 150px">
          <el-option v-for="a in ACTIONS" :key="a.v" :label="a.l" :value="a.v" />
        </el-select>
        <el-select v-model="f.userId" placeholder="全部用户" clearable filterable style="width: 180px">
          <el-option v-for="u in users" :key="u.id" :label="dispName(u)" :value="u.id" />
        </el-select>
        <el-input v-model="f.keyword" placeholder="关键字(detail/ip/action)" clearable style="width: 200px" @keyup.enter="load(true)" />
        <el-button type="primary" @click="load(true)">查询</el-button>
      </div>

      <!-- 日志列表:时间倒序 + 游标"加载更多" -->
      <div class="log-list">
        <div v-for="l in list" :key="l.id" class="log-item">
          <el-avatar :size="34" :src="resolveUrl(l.operatorAvatar)" class="log-avatar" />
          <div class="log-main">
            <div class="log-line1">
              <span class="log-op">{{ l.operatorName }}</span>
              <el-tag size="small" :type="actionTag(l.action)">{{ actionLabel(l.action) }}</el-tag>
              <span v-if="l.targetType" class="log-target">
                {{ l.targetType }}<template v-if="l.targetId"> #{{ l.targetId }}</template>
              </span>
            </div>
            <div v-if="l.detail" class="log-detail">{{ l.detail }}</div>
            <div class="log-meta">
              <span>{{ fmt(l.createTime) }}</span>
              <span v-if="l.ip && l.ip !== 'system'">· IP {{ l.ip }}</span>
            </div>
          </div>
        </div>
        <el-empty v-if="!loading && !list.length" description="没有匹配的日志" :image-size="70" />
      </div>

      <!-- 游标分页哨兵 -->
      <div class="sentinel">
        <div v-if="loading" class="sentinel-tip">加载中…</div>
        <div v-else-if="!hasMore && list.length" class="sentinel-tip">已到底</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { logs } from '@/api/audit'
import { mentionCandidates } from '@/api/auth'
import { displayName as dispName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

const router = useRouter()

// 动作枚举(与后端 audit 写入一致)
const ACTIONS = [
  { v: 'login', l: '登录' },
  { v: 'register', l: '注册' },
  { v: 'logout', l: '退出' },
  { v: 'update_profile', l: '改资料' },
  { v: 'create_post', l: '发帖' },
  { v: 'edit_post', l: '编辑帖子' },
  { v: 'delete_post', l: '删帖' },
  { v: 'signup', l: '活动报名' },
  { v: 'cancel_signup', l: '取消报名' },
  { v: 'blacklist', l: '拉黑' },
  { v: 'review_approve', l: '审核通过' },
  { v: 'review_reject', l: '审核驳回' },
]
const actionLabel = (v) => (ACTIONS.find((a) => a.v === v) || { l: v || '-' }).l
const actionTag = (v) => {
  if (v === 'review_approve') return 'success'
  if (v === 'review_reject' || v === 'delete_post' || v === 'blacklist') return 'danger'
  if (v === 'login' || v === 'register') return 'primary'
  return 'info'
}

// 筛选:动作 / 用户 / 关键字(都为空 = 全量)
const f = ref({ action: '', userId: null, keyword: '' })
const users = ref([])

// 游标分页状态
const list = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const observer = ref(null)

function fmt(t) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
}

async function load(reset) {
  if (loading.value) return
  loading.value = true
  try {
    if (reset) {
      cursor.value = null
      list.value = []
    }
    const data = await logs(cursor.value, 30, {
      action: f.value.action || undefined,
      userId: f.value.userId || undefined,
      keyword: f.value.keyword?.trim() || undefined,
    })
    list.value = reset ? data.list : [...list.value, ...data.list]
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
    await nextTick()
    setupObserver()
  } finally {
    loading.value = false
  }
}

function setupObserver() {
  observer.value?.disconnect()
  observer.value = new IntersectionObserver(
    (entries) => {
      if (entries[0].isIntersecting && !loading.value && hasMore.value) load(false)
    },
    { rootMargin: '200px' }
  )
  const el = document.querySelector('.sentinel')
  if (el) observer.value.observe(el)
}

// 筛选"用户"下拉:拉全部用户(排除自己、普通+管理员),用现成接口
async function loadUsers() {
  try {
    const all = await mentionCandidates('')
    users.value = Array.isArray(all) ? all : []
  } catch {
    users.value = []
  }
}

onMounted(() => {
  load(true)
  loadUsers()
})

onBeforeUnmount(() => {
  observer.value?.disconnect()
})
</script>

<style scoped>
.admin-inner {
  max-width: 780px;
  margin: 0 auto;
  padding: 16px 20px;
}
.filter-bar {
  display: flex;
  gap: 10px;
  margin: 16px 0;
  flex-wrap: wrap;
  padding: 14px 16px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--card-radius);
}
.log-list {
  display: flex;
  flex-direction: column;
}
.log-item {
  display: flex;
  gap: 12px;
  padding: 14px 12px;
  margin: 0 -12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  transition: background 0.15s;
}
.log-item:hover {
  background: var(--el-fill-color-light);
}
.log-main {
  flex: 1;
  min-width: 0;
}
.log-line1 {
  display: flex;
  align-items: center;
  gap: 8px;
}
.log-op {
  font-weight: 600;
  font-size: 14px;
}
.log-target {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.log-detail {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
.log-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.log-avatar {
  flex-shrink: 0;
}
.sentinel {
  min-height: 24px;
  text-align: center;
}
.sentinel-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 8px;
}
</style>
