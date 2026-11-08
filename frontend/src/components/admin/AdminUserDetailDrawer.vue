<template>
  <el-drawer
    v-model="open"
    title="用户详情"
    :size="drawerSize"
    direction="rtl"
    class="udetail-drawer"
    @close="emit('update:modelValue', false)"
  >
    <div v-if="loading" class="udetail__boot" aria-hidden="true">
      <span v-for="n in 4" :key="n" class="udetail__boot-line" />
    </div>

    <StateBlock
      v-else-if="failed"
      variant="error"
      title="详情没拉出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      @action="load"
    />

    <template v-else-if="d">
      <header class="udetail__head">
        <el-avatar :size="52" :src="resolveThumb(d.user.avatarUrl)">
          {{ (d.user.nickname || '友').slice(0, 1) }}
        </el-avatar>
        <div class="udetail__who">
          <p class="udetail__name">
            {{ d.user.nickname || '(未设置昵称)' }}
            <span v-if="d.user.role === 1" class="udetail__tag">管理员</span>
          </p>
          <p class="udetail__sub">{{ ageText(d.user) }}</p>
          <p class="udetail__sub">
            {{ d.user.studentNo || '未提交学号' }}<template v-if="d.user.dept"> · {{ d.user.dept }}</template>
          </p>
        </div>
        <span class="udetail__status" :data-status="d.user.status">{{ statusText(d.user) }}</span>
      </header>

      <div class="udetail__facts">
        <div class="udetail__fact" :data-warn="d.user.creditScore < 60 ? '1' : undefined">
          <b>{{ d.user.creditScore }}</b><span>信誉分</span>
        </div>
        <div class="udetail__fact" :data-warn="d.user.reportCount > 0 ? '1' : undefined">
          <b>{{ d.user.reportCount }}</b><span>被举报（成立 {{ d.user.upheldCount }}）</span>
        </div>
        <div class="udetail__fact">
          <b>{{ d.user.postCount }}</b><span>已发布帖子</span>
        </div>
        <div class="udetail__fact" :data-warn="d.user.banCount > 0 ? '1' : undefined">
          <b>{{ d.user.banCount }}</b><span>累计封禁</span>
        </div>
      </div>

      <section v-if="d.cancelled" class="udetail__sec">
        <h3 class="udetail__h">注销留存</h3>
        <p class="udetail__note">
          {{ d.cancelled.policy }}
        </p>
        <p class="udetail__meta">
          注销于 {{ fmt(d.cancelled.cancelledAt) }}
          · 计划清理 {{ fmt(d.cancelled.purgeDueAt) }}
          <template v-if="d.cancelled.purgedAt"> · 已清理 {{ fmt(d.cancelled.purgedAt) }}</template>
        </p>
      </section>

      <section class="udetail__sec">
        <h3 class="udetail__h">最近举报<span class="udetail__count">{{ d.reports.length }}</span></h3>
        <p v-if="!d.reports.length" class="udetail__empty">没有举报记录</p>
        <ul v-else class="udetail__list">
          <li v-for="r in d.reports" :key="r.id" class="udetail__row">
            <p class="udetail__row-title">
              {{ reportTitle(r) }}
              <span class="udetail__pill" :data-status="r.status">{{ REPORT_STATUS[r.status] || '未知' }}</span>
            </p>
            <p v-if="r.reasonText" class="udetail__row-body">{{ r.reasonText }}</p>
            <p class="udetail__meta">
              {{ fmt(r.createTime) }}
              <template v-if="r.handleTime"> · {{ actionLabel(r) }}</template>
              <template v-if="r.handleNote"> · {{ r.handleNote }}</template>
            </p>
          </li>
        </ul>
      </section>

      <section class="udetail__sec">
        <h3 class="udetail__h">处置轨迹<span class="udetail__count">{{ d.actions.length }}</span></h3>
        <p v-if="!d.actions.length" class="udetail__empty">没有处置记录</p>
        <ol v-else class="udetail__timeline">
          <li v-for="(a, i) in d.actions" :key="`${a.createTime}-${i}`" class="udetail__event">
            <span class="udetail__dot" aria-hidden="true" />
            <p class="udetail__row-title">
              {{ ACCOUNT_ACTION[a.action] || a.action }}
              <span class="udetail__meta">· {{ a.operator }} · {{ fmt(a.createTime) }}</span>
            </p>
            <p v-if="a.detail" class="udetail__row-body">{{ a.detail }}</p>
          </li>
        </ol>
      </section>

      <section class="udetail__sec">
        <h3 class="udetail__h">最近帖子<span class="udetail__count">{{ d.posts.length }}</span></h3>
        <p v-if="!d.posts.length" class="udetail__empty">还没有发过帖子</p>
        <ul v-else class="udetail__list">
          <li v-for="p in d.posts" :key="p.id" class="udetail__row">
            <component
              :is="p.status === 1 ? 'button' : 'p'"
              class="udetail__row-title udetail__post"
              :type="p.status === 1 ? 'button' : undefined"
              @click="p.status === 1 && router.push(`/post/${p.id}`)"
            >
              {{ p.excerpt || '(无文字内容)' }}
            </component>
            <p class="udetail__meta">
              {{ POST_STATUS[p.status] || '未知' }} · {{ fmt(p.createTime) }}
              · 赞 {{ p.likeCount }} · 评 {{ p.commentCount }}
            </p>
          </li>
        </ul>
      </section>
    </template>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import StateBlock from '@/components/common/StateBlock.vue'
import { adminUserDetail } from '@/api/admin'
import { resolveThumb } from '@/utils/resolve'
import { useIsMobile } from '@/composables/useBreakpoint'
import { ageText, statusText, REPORT_STATUS, HANDLE_ACTION, ACCOUNT_ACTION } from '@/utils/adminUserFormat'
import { reasonLabel, targetTypeLabel } from '@/constants/reportReason'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  userId: { type: [Number, String], default: null },
})
const emit = defineEmits(['update:modelValue'])

const router = useRouter()
const isMobile = useIsMobile()
const drawerSize = computed(() => (isMobile.value ? '100%' : '480px'))

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const d = ref(null)
const loading = ref(false)
const failed = ref(false)

const POST_STATUS = { 0: '审核中', 1: '已通过', 2: '已下架', 3: '已删除' }

function fmt(t) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '—'
}

function reportTitle(r) {
  return `${targetTypeLabel(r.targetType)} #${r.targetId} · ${reasonLabel(r.reasonType)}`
}

function actionLabel(r) {
  const action = HANDLE_ACTION[r.handleAction] || r.handleAction
  if (!action) return '已处置'
  return r.handleDays != null && ['ban', 'mute'].includes(r.handleAction)
    ? `${action} ${r.handleDays === 0 ? '永久' : `${r.handleDays} 天`}`
    : action
}

async function load() {
  if (!props.userId || loading.value) return
  loading.value = true
  failed.value = false
  try {
    d.value = await adminUserDetail(props.userId)
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.userId],
  ([visible]) => {
    if (visible) {
      d.value = null
      load()
    }
  },
  { immediate: true },
)
</script>

<style scoped src="./AdminUserDetailDrawer.css"></style>
