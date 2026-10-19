<template>
  <div class="af">
    <!-- 行 1:筛选条。待处理带全局未处置数,不只数本页 -->
    <div class="chips" role="tablist" aria-label="反馈状态">
      <button
        v-for="s in STATUS_CHIPS"
        :key="String(s.value)"
        type="button"
        role="tab"
        class="chip"
        :class="{ 'is-on': status === s.value }"
        :aria-selected="status === s.value"
        @click="pickStatus(s.value)"
      >
        {{ s.label }}
        <span v-if="s.value === 0 && pendingCount > 0" class="chip__num ys-num">{{ pendingCount }}</span>
      </button>
    </div>

    <p v-if="actionError" class="notice notice--danger" role="alert">{{ actionError }}</p>

    <!-- 权限不足(AC-20)。与 AdminReview / AdminAnnouncement 共用 StateBlock -->
    <StateBlock
      v-if="denied"
      variant="denied"
      title="这个页面只有管理员能看"
      description="如果你应该是管理员，找负责人开一下权限"
      action-label="回首页"
      :icon="VerifiedShield"
      @action="router.push('/')"
    />

    <template v-else>
      <SkeletonList v-if="loading" :count="5" />

      <StateBlock
        v-else-if="error"
        variant="error"
        title="反馈列表没拉出来"
        description="可能是网络断了，点一下重试"
        action-label="重试"
        :icon="WarningFilled"
        @action="load(true)"
      />

      <StateBlock
        v-else-if="all.length === 0"
        variant="empty"
        title="还没有收到反馈"
        description="用户在导航抽屉里提交的意见反馈会出现在这里"
        :icon="ChatLineSquare"
      />

      <StateBlock
        v-else-if="list.length === 0"
        variant="empty"
        title="这个状态下没有反馈"
        description="换一个筛选看看"
        action-label="看全部"
        :primary="false"
        :icon="Filter"
        @action="pickStatus(null)"
      />

      <template v-else>
        <ul class="af__list">
          <AdminFeedbackItem
            v-for="f in list"
            :key="f.id"
            :item="f"
            :busy="busyId === f.id"
            @adopt="openHandle(f, 'adopt')"
            @ignore="openHandle(f, 'ignore')"
          />
        </ul>

        <SkeletonList v-if="loadingMore" :count="2" variant="panel" />
        <p v-else-if="hasMore" class="more" @click="loadMore">上拉看更多</p>
      </template>
    </template>

    <SafeArea position="bottom" />

    <!--
      处置弹层。handleNote 必填(契约如此),但这里用 el-input + 明确必填标记,
      不用 el-form 的 rules:一个字段一个规则,el-form 的开销换不来任何东西。
    -->
    <el-dialog
      v-model="handleOpen"
      :title="handleAction === 'adopt' ? '采纳这条反馈' : '忽略这条反馈'"
      :width="isMobile ? '92%' : '480px'"
      append-to-body
    >
      <p class="af__quote">{{ pending?.content }}</p>
      <el-input
        v-model="handleNote"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        resize="none"
        :placeholder="handleAction === 'adopt' ? '写清楚打算怎么改' : '写一句为什么忽略'"
      />
      <template #footer>
        <el-button @click="handleOpen = false">取消</el-button>
        <el-button type="primary" :loading="handling" :disabled="!handleNote.trim()" @click="submitHandle">
          确认{{ handleAction === 'adopt' ? '采纳' : '忽略' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
/**
 * 意见反馈审核(2026-10-18)。数据流与 AdminAnnouncement 同构:
 * 游标分页 + 状态筛选 + 本地同步(不整列表重拉,否则手机上滚动位置会丢)。
 *
 * 409 的处理:后端乐观锁 WHERE status=0,两个管理员同时点只有一个成功。
 * api 层已 suppressStatuses:[409],所以这里把「已被他人处理」翻成人话 +
 * 刷新这一条的状态,而不是让全局红条先弹一条「操作失败」。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { ChatLineSquare, Filter, WarningFilled } from '@element-plus/icons-vue'
import { adminFeedback, handleFeedback } from '@/api/feedback'
import { useUserStore } from '@/stores/user'
import { useIsMobile } from '@/composables/useBreakpoint'
import AdminFeedbackItem from '@/components/admin/AdminFeedbackItem.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import SafeArea from '@/components/mobile/SafeArea.vue'
import VerifiedShield from '@/icons/VerifiedShield.vue'

/** 筛选 chip。null = 全部,与后端「不传 status」对应 */
const STATUS_CHIPS = [
  { value: 0, label: '待处理' },
  { value: 1, label: '已采纳' },
  { value: 2, label: '已忽略' },
  { value: null, label: '全部' },
]

const router = useRouter()
const userStore = useUserStore()
const isMobile = useIsMobile()

const all = ref([])
const status = ref(0)
const cursor = ref(null)
const hasMore = ref(false)
const pendingCount = ref(0)
const loading = ref(true)
const loadingMore = ref(false)
const error = ref(false)
const denied = ref(false)
const actionError = ref('')
const busyId = ref(null)

const list = all

/* ---- 处置弹层状态 ---- */
const handleOpen = ref(false)
const handling = ref(false)
const handleNote = ref('')
const handleAction = ref('adopt')
/** 正在处置的那条;处置成功后用它就地更新,不整列表重拉 */
const pending = ref(null)

async function load(reset = false) {
  if (reset) {
    loading.value = true
    error.value = false
  } else {
    loadingMore.value = true
  }
  try {
    const data = await adminFeedback({ status: status.value, cursor: reset ? null : cursor.value })
    const rows = data?.list || []
    all.value = reset ? rows : [...all.value, ...rows]
    cursor.value = data?.nextCursor ?? null
    hasMore.value = !!data?.hasMore
    pendingCount.value = data?.pendingCount ?? 0
  } catch (e) {
    // 403 是权限问题不是加载失败 —— 两者必须分开(AC-20)
    if (e?.response?.status === 403) denied.value = true
    else if (reset) error.value = true
    else actionError.value = '没拉出来，再点一次'
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  await load(false)
}

function pickStatus(v) {
  if (status.value === v) return
  status.value = v
  all.value = []
  cursor.value = null
  hasMore.value = false
  load(true)
}

function openHandle(item, action) {
  pending.value = item
  handleAction.value = action
  handleNote.value = ''
  actionError.value = ''
  handleOpen.value = true
}

async function submitHandle() {
  const item = pending.value
  const note = handleNote.value.trim()
  if (!item || !note || handling.value) return
  handling.value = true
  busyId.value = item.id
  actionError.value = ''
  try {
    await handleFeedback(item.id, { action: handleAction.value, handleNote: note })
    // 本地同步:接口不回完整对象,自己改 status 与处理说明,避免整列表重拉丢滚动位置
    item.status = handleAction.value === 'adopt' ? 1 : 2
    item.handleNote = note
    handleTimeNow(item)
    if (pendingCount.value > 0) pendingCount.value -= 1
    handleOpen.value = false
    ElMessage.success(handleAction.value === 'adopt' ? '已采纳' : '已忽略')
  } catch (e) {
    // 409 = 已被别人处置了:不是故障,是竞争结果。刷新列表让管理员看到最新状态
    if (e?.response?.status === 409) {
      handleOpen.value = false
      actionError.value = '这条反馈刚被其他管理员处理过，列表已刷新'
      load(true)
    }
    // 其余错误由 request.js 全局拦截器提示过,这里不重复弹
  } finally {
    handling.value = false
    busyId.value = null
  }
}

/** 本地补处置时间:契约里 handleTime 是 ISO 字符串,格式与 AdminAnnItem 一致 */
function handleTimeNow(item) {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  item.handleTime = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  // 路由守卫已拦非管理员,这里是第二道:localStorage 里的 userInfo 可能过期
  if (!userStore.isAdmin) {
    denied.value = true
    loading.value = false
    return
  }
  load(true)
})
</script>

<style scoped src="./AdminFeedback.css"></style>
