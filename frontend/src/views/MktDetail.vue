<template>
  <div class="mdetail">
    <div class="mdetail-inner">
      <!--
        加载/不存在:整页替换(计划 §软删 404)。
        顶部自绘返回条始终保留,与下方状态块独立。
      -->

      <!-- 块 1:返回(回广场 /mkt) -->
      <header class="md__bar">
        <button type="button" class="md__back" aria-label="返回" @click="router.push('/mkt')">
          <el-icon :size="20"><ArrowLeft /></el-icon>
        </button>
        <h1 class="md__title">活动详情</h1>
        <!-- 详情页右上角「举报」(计划 §举报) -->
        <button
          v-if="act"
          type="button"
          class="md__report"
          aria-label="举报这条活动"
          @click="openActivityReport"
        >
          <el-icon :size="20"><Warning /></el-icon>
        </button>
      </header>

      <StateBlock
        v-if="loadFailed"
        class="md__state"
        variant="error"
        title="这条活动不见了"
        description="可能已被发布者删除,或链接失效"
        action-label="回集市广场"
        :icon="WarningFilled"
        @action="router.push('/mkt')"
      />

      <div v-else-if="loading" class="md__loading" aria-hidden="true">
        <SkeletonList variant="audit" :count="1" />
        <SkeletonList variant="panel" :count="4" />
      </div>

      <template v-else-if="act">
        <!-- 块 2:大标签栏:一级分类 + 倒计时(游戏已并入下方主文案) -->
        <div class="md__tags">
          <span class="md__cat">{{ act.category }}</span>
          <span
            class="md__timer"
            :class="{ 'md__timer--warn': timerWarn, 'md__timer--expired': timerExpired }"
          >
            {{ timerText || '已到期' }}
          </span>
        </div>

        <!-- 块 3:大号主文案「分类 · 游戏」(完整,不截断) -->
        <h2 class="md__h">{{ act.mainText || act.title }}</h2>

        <!-- 块 4:完整详情正文(全展开,{{ }} 渲染) -->
        <div class="md__content">{{ act.content }}</div>

        <!-- 块 5:信息小卡片一行:地点/价格/期望人数/剩余倒计时 -->
        <div class="md__info">
          <div class="md__info-item">
            <el-icon :size="18"><Location /></el-icon>
            <div class="md__info-txt">
              <span class="md__info-k">地点</span>
              <span class="md__info-v">{{ act.location || '未填' }}</span>
            </div>
          </div>
          <div class="md__info-item">
            <el-icon :size="18"><Coin /></el-icon>
            <div class="md__info-txt">
              <span class="md__info-k">价格</span>
              <span class="md__info-v">{{ priceText }}</span>
            </div>
          </div>
          <div class="md__info-item">
            <el-icon :size="18"><User /></el-icon>
            <div class="md__info-txt">
              <span class="md__info-k">期望人数</span>
              <span class="md__info-v">{{ act.targetNum ? `${act.targetNum}人` : '不限' }}</span>
            </div>
          </div>
          <div class="md__info-item">
            <el-icon :size="18"><Timer /></el-icon>
            <div class="md__info-txt">
              <span class="md__info-k">剩余</span>
              <span
                class="md__info-v"
                :class="{ 'md__info-v--warn': timerWarn, 'md__info-v--expired': timerExpired }"
              >
                {{ timerText || '已到期' }}
              </span>
            </div>
          </div>
        </div>

        <!-- 免责声明(计划 §第 2 处:信息卡下方一行) -->
        <!-- 不用 emoji 前缀:check-sfc 门禁零 emoji,且项目的文案纪律就是不拿 emoji 当图标 -->
        <p class="md__disclaimer">
          温馨提示:本平台仅提供信息展示与线上沟通渠道。所有线下见面、交易,请务必提高安全意识,
          自行承担风险。请勿私下进行大额转账。
        </p>

        <!-- 块 6:分割横线 -->
        <el-divider />

        <!-- 块 7:发布者昵称(不展示微信/手机号) -->
        <div class="md__publisher">
          <!-- 头像走统一 InitialAvatar;契约里没有发布者 id,seed 用昵称(仅决定颜色) -->
          <InitialAvatar :size="32" :src="act.avatarUrl ? resolveThumb(act.avatarUrl) : ''" :seed="act.nickname || ''" :name="act.nickname || ''" />
          <div class="md__pub-txt">
            <p class="md__pub-name">{{ act.nickname || '匿名' }}</p>
            <p class="md__pub-sub">发布者</p>
          </div>
        </div>

        <!-- 块 8:分割线 → 公共留言区 -->
        <el-divider />
        <MktComments
          :list="comments"
          :loading="commentsLoading"
          :my-id="myId"
          @submit="onCommentSubmit"
          @report-comment="openCommentReport"
        />
      </template>
    </div>

    <!-- 举报浮层(计划 §举报:复用 ReportSheet,5 选项映射既有 reasonType) -->
    <!-- 活动本体:targetType=5(搭子) -->
    <ReportSheet
      v-model="actReportOpen"
      :target-type="5"
      :target-id="route.params.id"
      sheet-title="举报这条活动"
      :submit-fn="submitReport"
    />
    <!-- 单条留言:targetType=3(评论),targetId 是留言 id -->
    <ReportSheet
      v-model="cmtReportOpen"
      :target-type="3"
      :target-id="reportCommentId"
      sheet-title="举报这条留言"
      :submit-fn="submitReport"
    />
  </div>
</template>

<script setup>
/**
 * 集市活动详情页(计划 §详情页 8 段固定竖向流)
 * ------------------------------------------------------------------
 * 1 返回 / 2 大标签栏 / 3 大号标题 / 4 完整正文 / 5 信息小卡片一行
 * / 6 分割线 / 7 发布者昵称 / 8 分割线 + 公共留言区。
 * 软删 404 → 整页替换;过期/暂停/满员仍可看可留言(计划 §详情语义)。
 * 倒计时 1s 间隔,≤2h 橙红。沟通只走留言区,不展示任何对外联系方式。
 * XSS:正文/留言一律 {{ }} 插值,禁用 v-html。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'
import {
  ArrowLeft, Location, Coin, User, Timer, Warning, WarningFilled,
} from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import MktComments from '@/components/mkt/MktComments.vue'
import ReportSheet from '@/components/post/ReportSheet.vue'
import { useRemainingCountdown } from '@/composables/useRemainingCountdown'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { submitReport } from '@/api/review'
import * as mkt from '@/api/mkt'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const myId = computed(() => userStore.userInfo?.id)

const act = ref(null)
const loading = ref(true)
const loadFailed = ref(false)
const comments = ref([])
const commentsLoading = ref(false)

/* ---------------- 倒计时(1s,≤2h 橙红) ---------------- */
const { remainingText: timerText, warn: timerWarn, expired: timerExpired } =
  useRemainingCountdown(() => act.value?.expireTime)

/* ---------------- 价格文案 ---------------- */
const priceText = computed(() => {
  const p = act.value?.price
  if (p == null || Number(p) === 0) return '免费'
  return `¥${Number(p)}`
})

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    act.value = await mkt.mktDetail(route.params.id)
    await loadComments()
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

async function loadComments() {
  commentsLoading.value = true
  try {
    const data = await mkt.mktComments(route.params.id, null, 50)
    comments.value = data.list || []
  } catch {
    comments.value = []
  } finally {
    commentsLoading.value = false
  }
}

/** 发留言:走 mktComment(限流+敏感词)。MktComments 捕获错误保留草稿。 */
async function onCommentSubmit(text) {
  await mkt.mktComment(route.params.id, text)
  ElMessage.success('留言发出去了')
  await loadComments()
}

/* ---------------- 举报(计划 §举报:复用 ReportSheet,不改后端) ---------------- */
/**
 * ReportSheet 自带 5 类原因选择(reasonType 1–5)与「其他」补充,
 * 5 选项到既有 reasonType 的映射(计划 §举报映射)在组件内已完成:
 * 广告→2、其它→5。活动本体 targetType=5(搭子),留言 targetType=3(评论)。
 * 提交成功/幂等的提示由 ReportSheet 内部统一处理(「举报已受理」「已经举报过」),
 * 本页不再各自弹 toast。
 */
const actReportOpen = ref(false)
const cmtReportOpen = ref(false)
const reportCommentId = ref(null)

/** 详情页右上角「举报」:举报活动本体 */
function openActivityReport() {
  actReportOpen.value = true
}

/** 留言区每条右下角「举报」:先记下目标,再开举报表 */
function openCommentReport(c) {
  reportCommentId.value = c.id
  cmtReportOpen.value = true
}

onMounted(load)
</script>

<style scoped src="./MktDetail.css"></style>
