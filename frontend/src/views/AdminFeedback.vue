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

    <!-- 行 2:类型筛选。普通内容分类都排除申诉,「认证申诉」可单独点出来,与状态筛选叠加 -->
    <div class="chips chips--sub" role="tablist" aria-label="反馈类型">
      <button
        v-for="c in TYPE_CHIPS"
        :key="c.key"
        type="button"
        role="tab"
        class="chip"
        :class="{ 'is-on': typeIsOn(c), 'chip--appeal-filter': c.key === 'appeal' }"
        :aria-selected="typeIsOn(c)"
        @click="pickType(c)"
      >
        {{ c.label }}
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
        title="当前筛选下没有反馈"
        description="换一个状态或类型看看"
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
      :title="handleTitle"
      :width="isMobile ? '92%' : '480px'"
      append-to-body
    >
      <p class="af__quote">{{ pending?.content }}</p>
      <!--
        申诉人没有账号：站内通知触达不到，处理说明只能靠邮件。
        必须让管理员在写之前就知道「这段话会直接发给对方」，而不是当内部备注写。
      -->
      <p v-if="pendingIsAppeal" class="af__hint">
        申诉人还没有账号，这段说明会通过邮件发到 TA 填写的联系邮箱。
      </p>
      <!--
        必填标记（2026-10-21 补）。
        handleNote 是契约里的必填项，确认按钮在它为空时是 disabled 的 ——
        但原来界面上没有任何地方说明这件事：管理员打开弹层看到一个灰按钮，
        不知道该填什么才能点。实测「确认采纳」一直点不动就是这个原因。
        现在用一句可见的「必填」把这层规则说出来。
      -->
      <label class="af__note-label" for="af-note">
        处理说明
        <span class="af__req">必填</span>
        <span class="af__note-hint">{{ noteHintText }}</span>
      </label>
      <el-input
        id="af-note"
        v-model="handleNote"
        type="textarea"
        :rows="3"
        maxlength="200"
        show-word-limit
        resize="none"
        :placeholder="handlePlaceholder"
      />
      <template #footer>
        <el-button @click="handleOpen = false">取消</el-button>
        <el-button type="primary" :loading="handling" :disabled="!handleNote.trim()" @click="submitHandle">
          {{ handleConfirmText }}
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
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { ChatLineSquare, Filter, WarningFilled } from '@element-plus/icons-vue'
import { adminFeedback, handleFeedback, FEEDBACK_STATUS_TEXT, FEEDBACK_CATEGORIES } from '@/api/feedback'
import { useUserStore } from '@/stores/user'
import { useIsMobile } from '@/composables/useBreakpoint'
import AdminFeedbackItem from '@/components/admin/AdminFeedbackItem.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import SafeArea from '@/components/mobile/SafeArea.vue'
import VerifiedShield from '@/icons/VerifiedShield.vue'
import { useAdminPending } from '@/composables/useAdminPending'

/**
 * 筛选 chip。null = 全部,与后端「不传 status」对应。
 *
 * 【2026-10-24 改为从 FEEDBACK_STATUS_TEXT 派生】
 * 原先这里手写了「0待处理 1已采纳 2已忽略」，而 api/feedback.js 早就导出了
 * 同一份（AdminFeedbackItem.vue 用的正是后者）。两边各写一次没有东西保证一致 ——
 * 举报理由文案就是这么漂移过一次：用户报「色情」，管理端显示「广告营销」。
 * 现在标签只有一个出处，这里只决定「哪几个 + 顺序」。
 */
const STATUS_CHIPS = [
  ...Object.entries(FEEDBACK_STATUS_TEXT).map(([value, label]) => ({
    value: Number(value),
    label,
  })),
  { value: null, label: '全部' },
]

/**
 * 类型筛选 chip。普通内容分类(1~5)都带 kind=0(排除认证申诉),
 * 「认证申诉」带 kind=1(只看申诉),「全部类型」两个都为 null。
 * 这样申诉不再被塞进「使用问题」等分类,也能单独点出来。
 *
 * 【2026-10-24 文案改为派生】原先 c1~c5 的 label 在这里手写，
 * 与 api/feedback.js 的 FEEDBACK_CATEGORIES 是同一批字面量。
 * 两份并存正是举报理由文案漂移的成因（用户报「色情」、管理端显示「广告营销」）。
 * 这里只保留「接线」（key / kind），文案统一取自常量。
 *
 * 「认证申诉」不进派生：它不是一个 category，而是 kind=1 这个独立维度
 * （申诉提交时 category 也填了值，靠 category 区分不出来）。
 */
const TYPE_CHIPS = [
  { key: 'all', label: '全部类型', category: null, kind: null },
  ...FEEDBACK_CATEGORIES.map((c) => ({
    key: `c${c.value}`,
    label: c.label,
    category: c.value,
    kind: 0,
  })),
  { key: 'appeal', label: '认证申诉', category: null, kind: 1 },
]

/** 当前类型 chip 是否高亮:category 与 kind 两个维度都要对上 */
function typeIsOn(c) {
  return category.value === c.category && kind.value === c.kind
}

const router = useRouter()
const userStore = useUserStore()
const isMobile = useIsMobile()

const all = ref([])
const status = ref(0)
/** 内容分类筛选;null=全部类型 */
const category = ref(null)
/** 认证申诉标记:1=只看申诉 0=只看非申诉 null=不限。普通内容分类都带 0,把申诉排出去 */
const kind = ref(null)
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

/** 申诉前缀，与后端 FeedbackServiceImpl / AdminFeedbackItem 保持一致 */
const APPEAL_PREFIXES = ['【认证申诉】', '【封禁申诉】']
/** 申诉人无登录态，处置说明走邮件而非站内通知 */
const pendingIsAppeal = computed(() => APPEAL_PREFIXES.some((p) => String(pending.value?.content || '').startsWith(p)))

const handleTitle = computed(() => {
  if (pendingIsAppeal.value) return handleAction.value === 'adopt' ? '通过这条申诉' : '驳回这条申诉'
  return handleAction.value === 'adopt' ? '采纳这条反馈' : '忽略这条反馈'
})

const handlePlaceholder = computed(() => {
  if (pendingIsAppeal.value) return '这段说明会通过邮件发给申诉人，写清楚核实结果或该怎么处理'
  return handleAction.value === 'adopt' ? '写清楚打算怎么改' : '写一句为什么忽略'
})

const handleConfirmText = computed(() => {
  if (pendingIsAppeal.value) return handleAction.value === 'adopt' ? '确认通过' : '确认驳回'
  return handleAction.value === 'adopt' ? '确认采纳' : '确认忽略'
})

/**
 * 必填标记右侧那句短说明（2026-10-21）。
 *
 * 为什么不用 placeholder 就够了：placeholder 在 textarea 里会被输入内容顶掉，
 * 而且它是灰的、和正文混在一起，起不到「这是必填」的提示作用。
 * 管理员需要**在动手前**就知道这段字必须写、以及写给谁看。
 */
const noteHintText = computed(() => {
  if (pendingIsAppeal.value) return '这段会通过邮件发给申诉人'
  return handleAction.value === 'adopt' ? '反馈者会看到这段说明' : '仅内部记录，反馈者看不到'
})

async function load(reset = false) {
  if (reset) {
    loading.value = true
    error.value = false
  } else {
    loadingMore.value = true
  }
  try {
    const data = await adminFeedback({
      status: status.value,
      category: category.value,
      kind: kind.value,
      cursor: reset ? null : cursor.value,
    })
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

/** 切换类型筛选:与 pickStatus 同构,重置游标后从第一页重拉 */
function pickType(c) {
  if (typeIsOn(c)) return
  category.value = c.category
  kind.value = c.kind
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
    // 抽屉/侧栏聚合角标同步(2026-10-24 修:同核验/机审/举报三处的一致性问题)
    useAdminPending().refresh(true)
    handleOpen.value = false
    // 申诉的处理说明是发邮件给对方，提示里说清楚，避免管理员以为只是站内备注
    ElMessage.success(
      pendingIsAppeal.value
        ? (handleAction.value === 'adopt' ? '已通过，处理说明已发邮件' : '已驳回，处理说明已发邮件')
        : (handleAction.value === 'adopt' ? '已采纳' : '已忽略'),
    )
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
