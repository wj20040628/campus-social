<template>
  <div class="mdetail">
    <div class="mdetail-inner">
      <!--
        加载/不存在:整页替换(计划 §软删 404)。
        顶部自绘返回条始终保留,与下方状态块独立。
      -->

      <!--
        块 1 已删除（2026-10-21）。
        改版前这里有一层自绘顶栏（返回 + 「活动详情」+ 举报），
        而 MainLayout 的全站顶栏已经在同一屏里显示了「活动详情」和返回箭头 ——
        实测两条横条叠在一起（全站顶栏 y=0 高 56，自绘条 y=89 高 57），
        都写着同样的标题、都有返回箭头，用户不知道该点哪个。
        现在：返回交给全站顶栏；「举报」挪到内容区右上角（见 .md__report-inline）。
      -->

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
        <!-- 主列：桌面端固定 640px，移动端吃满（见 MktDetail.css 媒体查询） -->
        <div class="md__main">
        <!--
          块 0：发布者（2026-10-21 提到最前，原为块 7）。
          集市是「找一个人办事」的场景，用户第二个想知道的就是「这人靠谱吗」。
          改版前它被一大段免责声明隔在很后面，等于把最重要的信任信号藏了。
          现在放最上：头像 + 昵称 + 发布时间 + 「主页 ›」入口。
        -->
        <div class="md__publisher">
          <InitialAvatar :size="36" :src="act.avatarUrl ? resolveThumb(act.avatarUrl) : ''" :seed="act.nickname || ''" :name="act.nickname || ''" />
          <div class="md__pub-txt">
            <p class="md__pub-name">{{ act.nickname || '匿名' }}</p>
            <p class="md__pub-sub">{{ publishedText }}</p>
          </div>
          <!-- 点进主页能看到这人的历史活动与认证，是判断可信度的主要依据 -->
          <RouterLink v-if="act.userId" :to="`/user/${act.userId}`" class="md__pub-more">
            主页 ›
          </RouterLink>
        </div>

        <!--
          块 2:分类 + 副标签 + 倒计时同一排（2026-10-21）。
          举报原来在自绘顶栏右侧；顶栏删掉后挪到这里 ——
          它是低频动作，用文字按钮而不是图标，避免和主内容抢视觉。

          【副标签】改版前这里只渲染 category，subTag（游戏名，如「王者荣耀」）
          完全没有出现 —— 用户反馈「点进详情看不到王者荣耀那个标」。
          现在补上，样式与列表卡片一致（灰字，跟在分类后面）。
        -->
        <div class="md__tags">
          <span class="md__cat">{{ act.category }}</span>
          <span v-if="act.subTag" class="md__sub">{{ act.subTag }}</span>
          <span
            class="md__timer"
            :class="{ 'md__timer--warn': timerWarn, 'md__timer--expired': timerExpired }"
          >
            {{ timerText || '已到期' }}
          </span>
          <button
            type="button"
            class="md__report-inline"
            aria-label="举报这条活动"
            @click="openActivityReport"
          >
            举报
          </button>
        </div>

        <!--
          块 3:标题（2026-10-21 修「详情页看不到标题」）。
          后端有两个字段：
            title    —— 用户实际写的标题（「今晚开黑缺一个打野」）
            mainText —— derivedTitle 派生的「分类 · 游戏」（「游戏组队 · 王者荣耀」）
          改版前这里取 mainText 优先，于是标题恒等于「分类 · 副标签」，
          被下面的 showTitle 判成重复而整行不渲染 —— 用户什么都看不到。
          现在改为 title 优先（与列表卡片口径一致）。
        -->
        <h2 v-if="showTitle" class="md__h">{{ titleText }}</h2>

        <!-- 块 4:完整详情正文(全展开,{{ }} 渲染) -->
        <div class="md__content">{{ act.content }}</div>

        <!--
          块 5:信息区（2026-10-21 从 2×2 卡片改成竖向紧凑列表）。
          改版前是网格卡片：手机端 2×2（实测占 156px，每张卡只有一个图标 + 两行字），
          桌面端 1×4（每块 151px 宽）。这四项本质是**一组同质的键值对**，
          用大卡承托过于隆重，也把首屏竖向空间吃掉一大块。
          现在改成一张卡内 4 行，每行「图标 + 键 + 值」，手机和桌面同一套结构。
        -->
        <div class="md__info">
          <div class="md__info-row">
            <el-icon :size="16"><Location /></el-icon>
            <span class="md__info-k">地点</span>
            <span class="md__info-v">{{ act.location || '未填' }}</span>
          </div>
          <div class="md__info-row">
            <el-icon :size="16"><Coin /></el-icon>
            <span class="md__info-k">价格</span>
            <span class="md__info-v">{{ priceText }}</span>
          </div>
          <div class="md__info-row">
            <el-icon :size="16"><User /></el-icon>
            <span class="md__info-k">期望人数</span>
            <span class="md__info-v">{{ act.targetNum ? `${act.targetNum} 人` : '不限' }}</span>
          </div>
          <div class="md__info-row">
            <el-icon :size="16"><Timer /></el-icon>
            <span class="md__info-k">剩余</span>
            <span
              class="md__info-v"
              :class="{ 'md__info-v--warn': timerWarn, 'md__info-v--expired': timerExpired }"
            >
              {{ timerText || '已到期' }}
            </span>
          </div>
        </div>

        <!--
          留言区（2026-10-21 挪到信息卡之后）。
          改版前它前面隔着免责声明 + 分割线 + 发布者块 ——
          而发布者已提到最上、免责改成弹窗，于是只剩「读完信息就留言」这一条路径。
        -->
        <MktComments
          :list="comments"
          :loading="commentsLoading"
          :my-id="myId"
          :submit-comment="onCommentSubmit"
          @report-comment="openCommentReport"
        />

        <!--
          安全须知兜底（2026-10-21）。
          免责声明原本是一大段常驻文字，现在改成首次进入弹窗（RiskNotice）。这里保留
          一行 11px 灰字：既不再占据主体空间，又不至于让页面完全没有任何安全提示。
          点它可重新打开弹窗看全文。
        -->
        <p class="md__risk-foot">
          本平台不参与线下交易 ·
          <button type="button" class="md__risk-link" @click="reopenRisk">安全须知</button>
        </p>
        </div><!-- /.md__main -->

        <!--
          右栏（2026-10-21，抽成组件 MktDetailAside 以控制单文件行数）。
          仅桌面端显示，三块信息全部由已有数据算出，不新增接口。
        -->
        <MktDetailAside
          :status-text="statusText"
          :status-class="statusClass"
          :published-text="publishedText"
          :timer-text="timerText"
          :timer-warn="timerWarn"
          :timer-expired="timerExpired"
          :comment-count="comments.length"
          :category="act.category"
          :same-cat-count="sameCatCount"
        />
      </template>
    </div>

    <!-- 举报浮层(计划 §举报:复用 ReportSheet,5 选项映射既有 reasonType) -->
    <!-- 活动本体 targetType=6（集市活动）。原用 5，那是契约里的「搭子」，
         处置会被后端回 422「搭子需上线后处理」—— 举报能提交、管理员却不能处置。 -->
    <ReportSheet
      v-model="actReportOpen"
      :target-type="6"
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

    <!-- 安全须知弹窗（2026-10-21）：首次进入弹一次，勾选后本次登录内不再弹 -->
    <MktRiskNotice v-model="riskOpen" @ack="onRiskAck" />
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
  Location, Coin, User, Timer, WarningFilled,
} from '@element-plus/icons-vue'
import { relTime } from '@/utils/relTime'
import StateBlock from '@/components/common/StateBlock.vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import MktComments from '@/components/mkt/MktComments.vue'
import MktDetailAside from '@/components/mkt/MktDetailAside.vue'
import MktRiskNotice from '@/components/mkt/MktRiskNotice.vue'
import { useMktRiskNotice } from '@/composables/useMktRiskNotice'
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

/* ---------------- 标题（2026-10-21） ---------------- */
/**
 * 详情页标题。
 *
 * 后端有两个字段，含义不同：
 *   title    —— 用户实际写的标题，如「今晚开黑缺一个打野」
 *   mainText —— derivedTitle(category, subTag) 派生的「游戏组队 · 王者荣耀」
 *
 * 【为什么改 title 优先】改版前这里是 mainText 优先，于是：
 *   1. 用户写的标题从来没在详情页显示过；
 *   2. mainText 恰好等于「分类 · 副标签」，被下面的 showTitle 判成重复，
 *      整行标题因此不渲染 —— 用户看到的是「没有标题」。
 * 现在与列表卡片口径一致：title 优先，mainText 兜底（兼容旧数据）。
 */
const titleText = computed(() => act.value?.title || act.value?.mainText || '')

/**
 * 与分类标签重复的标题不渲染，那行字没有新信息。
 *
 * 只对「旧数据」生效：那些 title 是后端拿分类兜底存的，
 * 或压根没填标题、title 为空时落到 mainText。
 * 用户真正写过标题的（title 是独立内容）不会被误判。
 */
const showTitle = computed(() => {
  const t = titleText.value.trim()
  if (!t) return false
  const cat = String(act.value?.category || '').trim()
  const sub = String(act.value?.subTag || '').trim()
  if (cat && t === cat) return false
  if (cat && sub && t === `${cat} · ${sub}`) return false
  return true
})

/* ---------------- 发布信息（2026-10-21 加） ---------------- */
/**
 * 发布者副标题：相对时间 + 「发布」。
 *
 * 原来这里是写死的「发布者」三个字，等于把标题又抄了一遍。
 * 改成时间后，用户能判断这条活动有多新 —— 对限时集市是关键信息。
 * 时间缺失时退回「发布者」，不显示空白。
 */
const publishedText = computed(() => {
  const t = act.value?.createTime || act.value?.lastLaunchTime
  const rel = t ? relTime(t) : ''
  return rel ? `${rel}发布` : '发布者'
})

/* ---------------- 右栏数据（2026-10-21，仅桌面显示） ----------------
 * 状态文案与颜色：后端下发 status（LAUNCHING/FULL/PAUSED/EXPIRED），
 * 详情页此前没用它 —— 用户看不出这条是「还能加入」还是「已满」。
 * 同分类条数复用 GET /mkt/plaza 按分类查一次，不新增端点；
 * 失败静默置 null（模板 v-if 隐藏整块，它是增强信息）。
 */
const STATUS_TEXT = {
  LAUNCHING: '投放中',
  FULL: '已满员',
  PAUSED: '已暂停',
  EXPIRED: '已过期',
}

const statusText = computed(() => STATUS_TEXT[act.value?.status] || '投放中')

const statusClass = computed(() => {
  const s = act.value?.status
  if (s === 'EXPIRED') return 'panel__v--danger'
  if (s === 'PAUSED' || s === 'FULL') return 'panel__v--warn'
  return 'panel__v--live'
})

const sameCatCount = ref(null)

async function loadSameCat() {
  try {
    const data = await mkt.mktPlaza(act.value?.category || null, null, 20)
    const list = (data?.list || []).filter((a) => String(a.id) !== String(route.params.id))
    sameCatCount.value = list.length
  } catch {
    sameCatCount.value = null
  }
}

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    act.value = await mkt.mktDetail(route.params.id)
    await loadComments()
    // 右栏「同样在找」独立加载，失败静默（它是增强信息）
    loadSameCat()
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

/* ---------------- 举报（复用 ReportSheet，不改后端） ----------------
 * ReportSheet 自带 5 类原因（reasonType 1–5）与「其他」补充，
 * 提交成功/幂等的提示由它内部统一处理，本页不再各自弹 toast。
 * 活动本体 targetType=5（搭子），留言 targetType=3（评论）。
 */
const actReportOpen = ref(false)
const cmtReportOpen = ref(false)
const reportCommentId = ref(null)

/** 详情页右上角「举报」：举报活动本体 */
function openActivityReport() {
  actReportOpen.value = true
}

/** 留言区每条「举报」：先记下目标，再开举报表 */
function openCommentReport(c) {
  reportCommentId.value = c.id
  cmtReportOpen.value = true
}

/* ---------------- 安全须知弹窗（2026-10-21） ---------------- */
/*
 * 开关与「本次登录不再提醒」的记忆全部收在 useMktRiskNotice 里
 * （sessionStorage 实现，退出登录后下次登录再弹）。详见该 composable 的注释。
 */
const { riskOpen, maybeShowRisk, onRiskAck, reopenRisk } = useMktRiskNotice()

onMounted(() => {
  load()
  maybeShowRisk()
})
</script>

<style scoped src="./MktDetail.css"></style>
