<template>
  <div class="adetail">
    <div class="adetail-inner">
      <el-page-header @back="router.back()" :title="'活动详情'" />

      <el-card v-if="act">
        <div class="a-title">{{ act.title }}</div>
        <div class="a-meta">
          <el-tag :type="actStatusTag(act.status)">{{ actStatusText(act.status) }}</el-tag>
          <span>名额 {{ act.capacity === 0 ? '不限' : act.currentCount + ' / ' + act.capacity }}</span>
        </div>

        <!--
          名额进度（2026-10-21 加）。
          原来只有「3 / 10」一串数字，要自己算还剩几个；
          一条进度条 + 一句「还剩 7 个名额」让人一眼判断还来不来得及。
          capacity=0 表示不限名额，此时不画进度条（没有分母）。
        -->
        <div v-if="act.capacity > 0" class="a-quota">
          <div class="a-quota__row">
            <span class="a-quota__n ys-num">{{ act.currentCount }}</span>
            <span class="a-quota__t">/ {{ act.capacity }} 人已报名</span>
            <!--
              右侧那句话按活动状态分流（2026-10-21 修）。
              原来只判了 status!==4（已取消），于是**已结束/进行中**的活动
              也会出现「还剩 N 个名额」—— 报名通道早就关了，这句话是错的，
              会让人以为自己还能报。现在只在「报名中」才谈剩余。
            -->
            <span v-if="act.status === 4" class="a-quota__state">活动已取消</span>
            <span v-else-if="act.status === 3" class="a-quota__state">活动已结束</span>
            <span v-else-if="act.status === 2" class="a-quota__state">已开始</span>
            <span v-else-if="remainCount > 0" class="a-quota__left">
              还剩 {{ remainCount }} 个名额
            </span>
            <span v-else class="a-quota__full">名额已满</span>
          </div>
          <div class="a-quota__bar">
            <span class="a-quota__fill" :style="{ width: quotaPct + '%' }" />
          </div>
        </div>

        <div class="a-time">
          <el-icon><Calendar /></el-icon>
          <div class="a-time-box">
            <div>
              <span class="a-time-k">开始</span>
              {{ act.startTime ? dayjs(act.startTime).format('YYYY-MM-DD HH:mm') : '-' }}
            </div>
            <div>
              <span class="a-time-k">结束</span>
              {{ act.endTime ? dayjs(act.endTime).format('YYYY-MM-DD HH:mm') : '-' }}
            </div>
          </div>
        </div>

        <!--
          参加的人（2026-10-21 新增）。
          【为什么要有】改版前这里只有一个数字，报了名不知道同去的是谁 ——
          而这类活动常是线下见面，「知道谁去」直接影响来不来、也关系到安全。
          用 chip 而不是列表：多数活动 3~10 人，chip 更紧凑；
          超过 12 人折叠成「+N」，点开看完整名单。
        -->
        <div v-if="signups.length" class="a-signed">
          <div class="a-signed__h">
            <span class="a-signed__t">参加的人</span>
            <span class="a-signed__n">{{ signups.length }} 人</span>
            <button
              v-if="signups.length > CHIP_LIMIT"
              type="button"
              class="a-signed__more"
              @click="showAllSignups = !showAllSignups"
            >
              {{ showAllSignups ? '收起' : `看全部 ${signups.length} 人` }}
            </button>
          </div>
          <div class="a-signed__list">
            <span
              v-for="s in visibleSignups"
              :key="s.userId"
              class="a-chip"
              :class="{ 'a-chip--me': s.isMe }"
            >
              <span class="a-chip__av">
                <img v-if="s.avatarUrl" :src="resolveThumb(s.avatarUrl)" alt="" />
                <template v-else>{{ (s.nickname || '匿').slice(0, 1) }}</template>
              </span>
              <span class="a-chip__n">{{ s.nickname }}</span>
            </span>
            <span v-if="!showAllSignups && hiddenCount > 0" class="a-chip a-chip--more">
              +{{ hiddenCount }}
            </span>
          </div>
        </div>

        <el-divider />

        <div class="a-actions">
          <template v-if="act.signedUp">
            <el-tag type="success">已报名</el-tag>
            <!-- 已报名的提示：原来只有一枚「已报名」标签，用户不知道接下来还会发生什么 -->
            <span class="a-actions__hint">你的名字已出现在上面</span>
            <el-button v-if="canCancel" type="danger" plain @click="onCancel">取消报名</el-button>
          </template>
          <template v-else>
            <el-button
              type="primary"
              :disabled="!act.canSignup"
              :loading="signing"
              @click="onSignup"
            >
              立即报名
            </el-button>
            <el-text v-if="!act.canSignup" type="info">{{ signupBlockedReason(act) }}</el-text>
            <span v-else class="a-actions__hint">报名后你的名字会出现在上面</span>
          </template>
          <el-button
            v-if="isCreator && act.status !== 4"
            type="danger"
            plain
            @click="onCloseActivity"
          >
            取消活动
          </el-button>
          <!-- 分享卡片(2026-10-25):和报名同一操作区,报名前的犹豫期正是转发时机 -->
          <el-button plain @click="shareOpen = true">分享</el-button>
        </div>
      </el-card>
      <StateBlock v-else variant="error" title="这个活动不存在" description="可能已经结束或被发起人撤掉了" action-label="回圈子广场" :icon="WarningFilled" @action="router.push('/circles')" />
    </div>

    <!-- 分享卡片:扫码进来的人免登录可读活动详情与报名名单(后端已放游客 GET) -->
    <ShareCardSheet v-model:open="shareOpen" :spec="shareSpec" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import { Calendar, WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import ShareCardSheet from '@/components/share/ShareCardSheet.vue'
import { shareUrl } from '@/utils/shareCard'
import { resolveThumb } from '@/utils/resolve'
import { useUserStore } from '@/stores/user'
import {
  activityDetail,
  activitySignups,
  signup as signupApi,
  cancelSignup,
  closeActivity,
} from '@/api/circle'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const myId = computed(() => userStore.userInfo?.id)
const act = ref(null)
const signing = ref(false)

/** 报名者名单（2026-10-21 新增）。与 act 分开存：名单拉失败不影响活动本体渲染 */
const signups = ref([])
/** 超过 12 人先折叠，点「看全部」展开 */
const CHIP_LIMIT = 12
const showAllSignups = ref(false)

const visibleSignups = computed(() =>
  showAllSignups.value ? signups.value : signups.value.slice(0, CHIP_LIMIT),
)

const hiddenCount = computed(() => Math.max(0, signups.value.length - CHIP_LIMIT))

const remainCount = computed(() => {
  if (!act.value || !(act.value.capacity > 0)) return 0
  return Math.max(0, act.value.capacity - act.value.currentCount)
})

/** 进度条百分比。分母固定且 >0（capacity=0 时不渲染这条），所以不用担心除零 */
const quotaPct = computed(() => {
  if (!act.value || !(act.value.capacity > 0)) return 0
  return Math.min(100, Math.round((act.value.currentCount / act.value.capacity) * 100))
})

const isCreator = computed(
  () => !!act.value && !!act.value.creatorId && act.value.creatorId === myId.value
)

/* ---------------- 分享卡片(2026-10-25) ---------------- */
const shareOpen = ref(false)
const shareSpec = computed(() => {
  const a = act.value
  if (!a) return null
  return {
    kind: 'activity',
    title: a.title || '圈子活动',
    excerpt: a.startTime
      ? `开始时间 ${dayjs(a.startTime).format('MM-DD HH:mm')}`
      : '',
    authorName: '', // ActivityVO 不带圈名,卡片作者行留空不渲染
    topics: [actStatusText(a.status)],
    qrText: shareUrl(`/activity/${a.id}`),
    fileName: `yousheng-activity-${a.id}.png`,
  }
})

const canCancel = computed(() => {
  if (!act.value) return false
  // 报名中/已满 可取消;进行中/已结束不可
  return [0, 1].includes(act.value.status)
})

function actStatusText(s) {
  return ['报名中', '名额已满', '进行中', '已结束', '已取消'][s] || '未知'
}
function actStatusTag(s) {
  return ['primary', 'warning', 'success', 'info', 'danger'][s] || 'info'
}

/** 不可报名时的原因,按 status 细分,不笼统一句「不可报名」 */
function signupBlockedReason(a) {
  switch (Number(a?.status)) {
    case 1:
      return '名额已报满,关注下一场'
    case 2:
      return '活动已开打,报名通道关闭'
    case 3:
      return '活动已结束'
    case 4:
      return '活动已被取消'
    default:
      return '该活动当前不可报名'
  }
}

async function load() {
  act.value = await activityDetail(route.params.id)
  // 名单单独拉：它失败不该让整个页面进错误态（活动本体已经拿到了）
  try {
    signups.value = (await activitySignups(route.params.id)) || []
  } catch {
    signups.value = []
  }
}

async function onSignup() {
  signing.value = true
  try {
    await signupApi(route.params.id)
    ElMessage.success('报名成功')
    await load()
  } catch {
    // request.js 已弹错
  } finally {
    signing.value = false
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('确定取消报名?', '提示', { type: 'warning' })
  } catch {
    return
  }
  await cancelSignup(route.params.id)
  ElMessage.success('已取消报名')
  await load()
}

async function onCloseActivity() {
  try {
    await ElMessageBox.confirm('确定取消该活动?已报名的人会收到通知。', '取消活动', { type: 'warning' })
  } catch {
    return
  }
  try {
    await closeActivity(route.params.id)
    ElMessage.success('活动已取消,已通知报名者')
    await load()
  } catch {
    // request.js 已弹错
  }
}

onMounted(load)
</script>

<style scoped src="./ActivityDetail.css"></style>

