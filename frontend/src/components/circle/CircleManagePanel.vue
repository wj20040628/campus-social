<template>
  <el-drawer
    v-model="open"
    title="圈管理"
    :size="drawerSize"
    direction="rtl"
    class="cm-drawer"
    @close="emit('update:visible', false)"
  >
    <template v-if="myRole <= 2">
      <!--
        待审帖子(2026-10-24 圈主协审):仅圈主可见——服务端只认 owner_id,
        圈管理(role=2)点了必 403,前端不给入口。
        队列由服务端按身份返回:自己圈内、未超升级窗(默认 24h)的任务;
        超窗自动滑入平台队列,这里自然消失,前端不用算时间。
      -->
      <section v-if="myRole === 1" class="cm__section">
        <h3 class="cm__h">待审帖子<span v-if="reviewQ.total.value" class="cm__review-count">{{ reviewQ.total.value }}</span></h3>
        <div v-if="reviewQ.loading.value" class="cm__loading">加载中…</div>
        <div v-else-if="reviewQ.failed.value" class="cm__empty">待审列表没拉出来,关开抽屉重试</div>
        <div v-else-if="!reviewQ.list.value.length" class="cm__empty">没有待审的帖子</div>
        <ul v-else class="cm__review-list">
          <li v-for="t in reviewQ.list.value" :key="t.id" class="cm__review-item">
            <p class="cm__review-author">
              <!--
                作者可点跳主页(2026-10-24 补):圈主协审的判断依据同平台审核台——
                看看这个人平时在圈里发什么。帖子本体不跳(待审对非作者不可见)。
              -->
              <button
                v-if="t.author?.id"
                type="button"
                class="cm__review-author-link"
                @click="router.push(`/user/${t.author.id}`)"
              >
                {{ t.author?.nickname || '未知用户' }}
              </button>
              <template v-else>未知用户</template>
              <span v-if="t.hitWords?.length" class="cm__review-hits">命中:{{ t.hitWords.slice(0, 3).join('、') }}</span>
            </p>
            <p class="cm__review-content">{{ t.content }}</p>
            <input
              v-model="reviewQ.reasons[t.id]"
              type="text"
              maxlength="512"
              class="cm__review-reason"
              placeholder="驳回理由(驳回时必填,作者会看到)"
            />
            <div class="cm__review-btns">
              <button
                type="button"
                class="cm__btn"
                :disabled="reviewQ.busy[t.id]"
                @click="reviewQ.approveOne(t)"
              >通过</button>
              <button
                type="button"
                class="cm__btn cm__btn--warn"
                :disabled="reviewQ.busy[t.id]"
                @click="reviewQ.rejectOne(t)"
              >驳回</button>
            </div>
          </li>
        </ul>
        <p class="cm__review-hint">超过 24 小时未处理的任务会自动转给平台管理员协审</p>
      </section>

      <!-- 成员列表:圈主全功能,圈管理仅封禁/解禁(任命入口隐藏) -->
      <section class="cm__section">
        <h3 class="cm__h">成员</h3>
        <div v-if="loading" class="cm__loading">加载中…</div>
        <div v-else-if="!members.length" class="cm__empty">还没有成员</div>
        <ul v-else class="cm__list">
          <li v-for="m in members" :key="m.userId" class="cm__row" :class="{ 'is-banned': m.banStatus === 1 }">
            <span class="cm__avatar" aria-hidden="true">{{ (m.nickname || '?').slice(0, 1) }}</span>
            <div class="cm__id">
              <span class="cm__nick">{{ m.nickname }}</span>
              <span class="cm__role-tag">{{ roleText(m) }}</span>
            </div>
            <!-- 操作组:按 myRole + 目标 role 逐项显隐 -->
            <div class="cm__acts">
              <button
                v-if="myRole === 1 && m.role === 3 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.appoint(m)"
              >任命管理</button>
              <button
                v-if="myRole === 1 && m.role === 2 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.revoke(m)"
              >取消管理</button>
              <button
                v-if="m.role === 3 && m.userId !== me"
                type="button"
                class="cm__btn cm__btn--warn"
                @click="promptBan(m)"
              >{{ m.banStatus === 1 ? '改封禁' : '封禁' }}</button>
              <button
                v-if="m.role === 3 && m.banStatus === 1 && m.userId !== me"
                type="button"
                class="cm__btn"
                @click="actions.unban(m)"
              >解禁</button>
            </div>
          </li>
        </ul>
      </section>

      <!--
        解散区:仅圈主(myRole=1)可见。圈管理(role=2)不可解散 —— 后端
        requireCircleCreator 只认 role=1,圈管理点了必 403,所以前端直接不给。
        危险色描边 + 二次确认,与成员列表视觉分隔(管理面板底部独立区)。
      -->
      <section v-if="myRole === 1" class="cm__section cm__section--danger">
        <h3 class="cm__h">解散圈子</h3>
        <p class="cm__danger-hint">
          解散后圈子与圈内帖子全部消失,成员需重新建圈加入。此操作不可恢复。
        </p>
        <button
          type="button"
          class="cm__btn cm__btn--danger"
          :disabled="disbanding"
          @click="disband"
        >
          {{ disbanding ? '解散中' : '解散圈子' }}
        </button>
      </section>
    </template>

    <template v-else>
      <div class="cm__denied">你还没有管理权限</div>
    </template>
  </el-drawer>
</template>

<script setup>
import { ref, watch, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useIsMobile } from '@/composables/useBreakpoint'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { circleMembers, disband as disbandApi } from '@/api/circle'
import { useCircleAdminActions } from '@/composables/useCircleAdminActions'
import { useCircleReviewQueue } from '@/composables/useCircleReviewQueue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  circleId: { type: [Number, String], required: true },
  /** 当前查看者自己的圈内 role:1 圈主 / 2 圈管理 / 3 成员 / 0 非成员 */
  myRole: { type: Number, default: 0 },
  me: { type: [Number, String], default: 0 },
})
const emit = defineEmits(['update:visible', 'refresh-members', 'disband'])

const router = useRouter()

const isMobile = useIsMobile()
const drawerSize = computed(() => (isMobile.value ? '100%' : '420px'))

const open = ref(props.visible)
watch(() => props.visible, (v) => { open.value = v })

const loading = ref(false)
const members = ref([])
/** 解散圈子请求进行中:「解散」按钮进入「解散中」态并禁点 */
const disbanding = ref(false)

async function reload() {
  if (!props.myRole || props.myRole > 2) return
  loading.value = true
  try {
    const data = await circleMembers(props.circleId)
    members.value = data?.list || data || []
  } catch {
    members.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 解散圈子(仅圈主 myRole=1):软删圈子 + 级联软删圈内帖子,不可恢复。
 * 二次确认(ElMessageBox error 型)后调 POST /circle/{id}/disband,
 * 成功后通知父组件重载(圈子已软删,详情页会进 notFound 错误态)。
 */
async function disband() {
  try {
    await ElMessageBox.confirm(
      '解散后圈子与圈内帖子全部消失,成员需重新建圈加入。此操作不可恢复,确定解散?',
      '解散圈子',
      { type: 'error', confirmButtonText: '解散', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  disbanding.value = true
  try {
    await disbandApi(props.circleId)
    ElMessage.success('已解散圈子')
    emit('disband')
  } catch {
    ElMessage.error('解散失败,再试一次')
  } finally {
    disbanding.value = false
  }
}

// 成员处置动作(封禁/解禁)——置顶/下架/移帖在帖子卡上,这里只管成员
const actions = useCircleAdminActions(ref(props.circleId), null, reload)

// 圈主协审队列(2026-10-24):仅圈主拉,圈管理/成员不请求
const reviewQ = useCircleReviewQueue()
watch(open, (v) => {
  if (v && props.myRole === 1) {
    reviewQ.load()
  }
})

function roleText(m) {
  if (m.role === 1) return '圈主'
  if (m.role === 2) return '圈管理'
  return m.banStatus === 1 ? '被封禁' : '成员'
}

/** 封禁:选 长期 / 指定时长(小时)。until=null 长期。 */
async function promptBan(m) {
  const { value } = await ElMessageBox.prompt(
    `封禁时长(小时)。留空或填 0 = 长期封禁。`,
    '封禁成员',
    {
      confirmButtonText: '封禁',
      cancelButtonText: '取消',
      inputType: 'number',
      inputPlaceholder: '小时数,留空=长期',
      inputPattern: /^(0|[1-9]\d*)?$/,
      inputErrorMessage: '填小时数(正整数)或留空表示长期',
    },
  ).catch(() => ({ value: undefined, canceled: true }))
  if (value === undefined || value === '') return
  const hours = Number(value)
  if (Number.isNaN(hours) || hours < 0) return
  if (hours === 0) {
    await actions.ban(m, null) // 长期
  } else {
    const until = new Date(Date.now() + hours * 3600_000)
    // 后端收东八区 datetime,序列化为 'YYYY-MM-DDTHH:mm:ss'
    const pad = (n) => String(n).padStart(2, '0')
    const s = `${until.getFullYear()}-${pad(until.getMonth() + 1)}-${pad(until.getDate())}T${pad(until.getHours())}:${pad(until.getMinutes())}:${pad(until.getSeconds())}`
    await actions.ban(m, s)
  }
}

watch(open, (v) => {
  if (v) {
    reload()
    emit('refresh-members')
  }
})
onMounted(reload)

// 让父组件在「成员处置成功」后能重拉——actions 内部 reload 已覆盖
</script>

<style scoped src="./CircleManagePanel.css"></style>
