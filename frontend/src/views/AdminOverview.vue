<template>
  <!--
    管理总览（2026-11-07 新增）。

    此前管理端只有五个入口上的待办角标，没有一屏能回答
    「今天有没有异常、站内现在多大规模」。本页把待办、今日、规模、风控
    四组数字放在一起，待办卡可直接跳对应处置页。

    数据全部由 GET /admin/overview 一次返回；待办口径与入口角标同源
    （后端复用 pendingSummary），两处数字不会对不上。
  -->
  <div class="ov">
    <div v-if="!ready && !denied" class="ov__boot" aria-hidden="true">
      <span v-for="n in 6" :key="n" class="ov__boot-card" />
    </div>

    <AdminDenied v-else-if="denied" class="ov__state" @action="router.replace('/')" />

    <template v-else>
      <header class="ov__bar">
        <div class="ov__head">
          <h1 class="ov__title">
            <button type="button" class="ov__back" aria-label="返回" @click="router.back()">
              <ArrowLeft class="ov__back-icon" aria-hidden="true" />
              <span>管理总览</span>
            </button>
          </h1>
          <button
            type="button"
            class="ov__refresh"
            :disabled="loading"
            aria-label="刷新管理后台数据"
            @click="refreshAll"
          >
            <Refresh class="ov__refresh-icon" :class="{ 'ov__refresh-icon--spin': loading }" aria-hidden="true" />
            <span>{{ loading ? '刷新中' : '刷新' }}</span>
          </button>
        </div>
        <p class="ov__hint">
          今日从 00:00 起算<template v-if="updatedAt"> · 更新于 {{ updatedAt }}</template>
        </p>
        <p v-if="aiStatus" class="ov__ai" :data-ready="aiStatus.ready ? '1' : '0'">
          <span class="ov__ai-dot" aria-hidden="true" />
          <span>AI 机审：{{ aiStatus.hint }}</span>
        </p>
        <p v-else-if="data && !loading" class="ov__ai" data-ready="0">
          <span class="ov__ai-dot" aria-hidden="true" />
          <span>AI 机审：状态未取到，刷新后重试</span>
        </p>
      </header>

      <StateBlock
        v-if="failed && !data"
        variant="error"
        title="总览没拉出来"
        description="可能是网络断了，点一下重试"
        :icon="WarningFilled"
        action-label="重试"
        @action="load"
      />

      <template v-else-if="data">
        <section class="ov__sec">
          <div class="ov__sec-head">
            <h2 class="ov__sec-title">待办</h2>
            <span class="ov__sec-meta">共 {{ pendingTotal }} 项</span>
          </div>
          <div class="ov__grid ov__grid--todo">
            <button
              v-for="q in pendingCards"
              :key="q.t"
              type="button"
              class="ov__card ov__card--link"
              :class="{ 'ov__card--hot': q.n > 0 }"
              :data-alert="q.alert ? '1' : undefined"
              @click="router.push(q.path)"
            >
              <span class="ov__num">{{ format(q.n) }}</span>
              <span class="ov__label">{{ q.t }}</span>
              <span v-if="q.sub" class="ov__delta" data-dir="alert">{{ q.sub }}</span>
            </button>
          </div>
        </section>

        <section class="ov__sec">
          <div class="ov__sec-head">
            <h2 class="ov__sec-title">今日</h2>
          </div>
          <div class="ov__grid ov__grid--three">
            <div v-for="c in todayCards" :key="c.t" class="ov__card">
              <span class="ov__num">{{ format(c.n) }}</span>
              <span class="ov__label">{{ c.t }}</span>
              <span class="ov__delta" :data-dir="c.dir">{{ c.delta }}</span>
            </div>
          </div>
        </section>

        <section class="ov__sec">
          <div class="ov__sec-head">
            <h2 class="ov__sec-title">站内规模</h2>
          </div>
          <div class="ov__grid ov__grid--scale">
            <div v-for="c in scaleCards" :key="c.t" class="ov__card ov__card--dense">
              <span class="ov__num">{{ format(c.n) }}</span>
              <span class="ov__label">{{ c.t }}</span>
            </div>
          </div>
        </section>

        <section class="ov__sec">
          <div class="ov__sec-head">
            <h2 class="ov__sec-title">风控</h2>
          </div>
          <div class="ov__grid ov__grid--two">
            <div class="ov__card">
              <span class="ov__num">{{ format(data.bannedUsers) }}</span>
              <span class="ov__label">封禁中</span>
            </div>
            <div class="ov__card">
              <span class="ov__num">{{ format(data.mutedUsers) }}</span>
              <span class="ov__label">禁言中</span>
            </div>
          </div>
          <p class="ov__note">
            封禁中的账号不能登录；禁言中的可以登录浏览，但不能发帖、评论、私信和发群消息。
          </p>
        </section>
      </template>

      <StateBlock
        v-else-if="!loading"
        variant="empty"
        title="没有数据"
        description="稍后刷新再看看"
        :icon="WarningFilled"
      />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { adminOverview, adminReviewAiStatus } from '@/api/admin'
import { useUserStore } from '@/stores/user'
import { useAdminPending } from '@/composables/useAdminPending'
import { ElMessage } from '@/utils/feedback'
import AdminDenied from '@/components/common/AdminDenied.vue'
import StateBlock from '@/components/common/StateBlock.vue'

const router = useRouter()
const userStore = useUserStore()

const data = ref(null)
const loading = ref(false)
const failed = ref(false)
const updatedAt = ref('')
const aiStatus = ref(null)
const adminPending = useAdminPending()

const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

const pendingCards = computed(() => {
  const p = data.value?.pending || {}
  const d = data.value || {}
  const reportOld = d.oldestPendingReportHours || 0
  const reviewOld = d.oldestPendingReviewHours || 0
  /*
   * 告警阈值（2026-11-08）：不做趋势图，先把「该先干哪件」变成颜色。
   * 举报积压 >5 条或最久 >24h、机审最久 >24h 才标红，
   * 避免新站每天几条待办就满屏红字，管理员很快会学会无视颜色。
   */
  return [
    {
      t: '举报待处理', n: p.reportPending || 0, path: '/admin/reports',
      alert: reportOld >= 24 || (p.reportPending || 0) > 5,
      sub: reportOld ? `最久 ${reportOld} 小时` : '',
    },
    {
      t: '内容待审', n: p.reviewPending || 0, path: '/admin/review',
      alert: reviewOld >= 24,
      sub: reviewOld ? `最久 ${reviewOld} 小时` : '',
    },
    { t: '文件待审', n: p.filePending || 0, path: '/admin/files' },
    { t: '校园信息核对', n: p.verifyPending || 0, path: '/admin/verify' },
    { t: '意见反馈', n: p.feedbackPending || 0, path: '/admin/feedback' },
  ]
})

const todayCards = computed(() => {
  const d = data.value || {}
  return [
    deltaCard('新增用户', d.todayNewUsers, d.yesterdayNewUsers),
    deltaCard('新增帖子', d.todayNewPosts, d.yesterdayNewPosts),
    deltaCard('新增举报', d.todayReports, d.yesterdayReports),
  ]
})

/** 今日 vs 昨日：持平也明说，空白会让人以为是没加载出来 */
function deltaCard(t, today, yesterday) {
  const n = Number(today || 0)
  const prev = Number(yesterday || 0)
  const diff = n - prev
  return {
    t, n,
    delta: diff === 0 ? '较昨日持平' : `较昨日 ${diff > 0 ? '+' : ''}${diff}`,
    dir: diff === 0 ? 'flat' : diff > 0 ? 'up' : 'down',
  }
}

const pendingTotal = computed(() => pendingCards.value.reduce((sum, q) => sum + q.n, 0))

const scaleCards = computed(() => {
  const d = data.value || {}
  return [
    { t: '注册用户', n: d.activeUsers },
    { t: '已注销', n: d.cancelledUsers },
    { t: '已发布帖子', n: d.posts },
    { t: '评论', n: d.comments },
    { t: '正常圈子', n: d.circles },
    { t: '正常群组', n: d.groups },
    { t: '在投集市', n: d.mktActive },
    { t: '已发布公告', n: d.announcements },
  ]
})

function format(n) {
  return Number(n || 0).toLocaleString('zh-CN')
}

async function load() {
  if (loading.value) return
  loading.value = true
  failed.value = false
  try {
    const aiPromise = adminReviewAiStatus().catch(() => null)
    data.value = await adminOverview()
    aiStatus.value = await aiPromise
    updatedAt.value = new Date().toLocaleTimeString('zh-CN', {
      hour: '2-digit', minute: '2-digit', second: '2-digit',
    })
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 刷新按钮：总览数字 + AI 状态 + 侧栏/抽屉待办角标一起拉最新 */
async function refreshAll() {
  if (loading.value) return
  adminPending.refresh(true)
  await load()
  if (failed.value && data.value) ElMessage.error('刷新失败，请稍后重试')
}

onMounted(load)

watch(
  () => userStore.isAdmin,
  (v) => {
    // 权限在会话中途被收回：立刻离开，别让运营数字留在屏幕上
    if (v === false) router.replace('/')
  },
)
</script>

<style scoped src="./AdminOverview.css"></style>
