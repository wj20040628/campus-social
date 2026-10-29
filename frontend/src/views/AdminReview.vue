<template>
  <!--
    审核处置(AdminReview.vue.md)

    两个内容域(设计 §0):
      举报处置 —— 默认落点。OD-19 的时效压力来自举报,不是机审队列。
      内容审核 —— 机审队列,任务可以等。

    本文件只做**编排**:顶栏、Tab、权限不足、把两个域挂上去。
    业务逻辑全在下面三处,各有独立的数据源与错误语义:
      components/admin/ReportPane.vue   举报域
      composables/useReviewQueue.js     机审队列的分页 + 处置
      composables/useUndoWindow.js      两域共用的 5 秒撤销

    为什么顶栏不用 el-page-header:它在移动端是 60px 高的块状条,
    与 MainLayout 的 56px 自定义顶栏不同高,两套顶栏叠在一起会错位。
  -->
  <div class="review-admin">
    <!--
      userInfo 还没到时先给骨架,不是空页。
      直接跳空页会把「加载中」误判成「无权限」——
      管理员明明有权限却看到「只有管理员能看」。
    -->
    <div v-if="!ready && !denied" class="review-admin__boot" aria-hidden="true">
      <span class="review-admin__boot-line" />
      <span class="review-admin__boot-line review-admin__boot-line--short" />
    </div>

    <!--
      权限不足。与 AdminAudit 共用同一组件(设计 §3:两页必须完全一致,
      各写一份的话改文案时必然漏掉一处)。
    -->
    <AdminDenied v-else-if="denied" class="review-admin__state" @action="router.replace('/')" />

    <template v-else>
      <header class="review-admin__bar">
        <h1 class="review-admin__title">
          <button type="button" class="review-admin__back" aria-label="返回" @click="router.back()">
            <ArrowLeft class="review-admin__back-icon" aria-hidden="true" />
            <span>内容审核</span>
          </button>
        </h1>
        <p class="review-admin__hint">
          {{ tabHint }}
        </p>
      </header>

      <!--
        2026-10-25 重组:举报拆独立页(AdminReports),本页只管机审域。
        tab 从 4 个减到 2 个,页面名与 tab 名同域("内容审核"),不再三个词两个域。
      -->
      <el-tabs v-model="tab" class="review-admin__tabs" @tab-change="onTabChange">
        <el-tab-pane :label="`待审核${pendingCount ? ` (${pendingCount})` : ''}`" name="review" />
        <!-- 机审记录:驳回过哪些帖子、为什么驳,处置历史回查 -->
        <el-tab-pane label="记录" name="reviewHistory" />
      </el-tabs>

      <!-- 机审队列 -->
      <div v-if="tab === 'review' || tab === 'reviewHistory'" class="task-list">
        <!--
          快捷键说明。设计 §2 把 J/K/A/R/U 定为「桌面管理员的刚需」,
          但没给任何发现机制 —— 管理员不知道键位,快捷键等于没做。
          只在桌面端出现:移动端无物理键盘,列出来是噪音。
        -->
        <p v-if="!isMobile" class="review-admin__keys">
          <span><kbd>J</kbd><kbd>K</kbd> 切换</span>
          <span><kbd>A</kbd> 通过</span>
          <span><kbd>R</kbd> 驳回</span>
          <span><kbd>U</kbd> 撤销</span>
        </p>
        <ul v-if="loading && list.length === 0" class="task-skeletons" aria-hidden="true">
          <li v-for="n in 3" :key="n" class="task-skeleton">
            <span class="task-skeleton__line task-skeleton__line--who" />
            <span class="task-skeleton__line" />
            <span class="task-skeleton__line" />
            <span class="task-skeleton__line task-skeleton__line--short" />
          </li>
        </ul>

        <ul v-else class="task-list__items">
          <!--
            tabindex="-1" + ref:设计 §5 要求「切换条目时程序化聚焦,
            让屏幕阅读器读出新条目」。不写 -1 的话这个 li 不可聚焦,
            watch 里调 focus() 会被浏览器忽略(静默失败)。
            ref 用函数式,currentIndex 变化时 Vue 会重新绑定,
            配合 watch 触发聚焦。
          -->
          <li
            v-for="(t, i) in list"
            :key="t.id"
            :ref="(el) => setCardRef(t.id, el)"
            tabindex="-1"
            class="task-card"
            :class="{ 'task-card--current': !isMobile && i === currentIndex }"
          >
            <div class="task-head">
              <el-avatar :size="36" :src="resolveThumb(t.author?.avatarUrl)" />
              <div class="task-author">
                <!--
                  作者可点跳主页(2026-10-24 补):处置机审任务的辅助依据是
                  「这个人平时发什么」,昵称一直是纯文本,管理员得手拼 URL。
                  帖子本体不跳:待审帖(status=0)对非作者不可见,详情页会 404。
                -->
                <button
                  v-if="t.author?.id"
                  type="button"
                  class="task-author__name task-author__name--link"
                  @click="router.push(`/user/${t.author.id}`)"
                >
                  {{ displayName(t.author) }}
                </button>
                <p v-else class="task-author__name">未知用户</p>
                <p class="task-author__time">{{ fmt(t.createTime) }}</p>
              </div>
              <span class="task-status" :data-status="t.status">{{ statusText(t.status) }}</span>
            </div>

            <p class="task-content">{{ t.content }}</p>

            <p v-if="t.hitWords?.length" class="task-hitwords">
              <span class="task-hitwords__label">命中词</span>
              <span v-for="w in t.hitWords.slice(0, 6)" :key="w" class="task-chip">{{ w }}</span>
              <span v-if="t.hitWords.length > 6" class="task-chip task-chip--more">
                +{{ t.hitWords.length - 6 }}
              </span>
            </p>

            <!-- AI 机审预判(有 ai_verdict 才显示;人审可覆盖) -->
            <div v-if="t.aiVerdict" class="task-ai">
              <p class="task-ai__verdict" :data-verdict="t.aiVerdict">
                AI 机审:{{ aiVerdictText(t.aiVerdict) }}
              </p>
              <p v-if="t.aiReason" class="task-ai__reason">{{ t.aiReason }}</p>
            </div>

            <p v-if="t.status === 2 && t.reason" class="task-reason">驳回理由:{{ t.reason }}</p>

            <!--
              处置区。设计 §1.4:一次点击即生效 + 5 秒撤销,
              驳回要理由所以输入框常驻在按钮上方(不是弹窗)。
            -->
            <div v-if="t.status === 0" class="task-actions">
              <div class="task-reason-input">
                <label class="task-reason-input__label" :for="`review-reason-${t.id}`">
                  驳回理由(发布者会看到)
                </label>
                <!--
                  常用理由（2026-10-28）：机审队列一屏三五条，同一种违规
                  要手打三遍「含敏感词」。点一下填进输入框，还能再改。
                -->
                <div class="task-presets">
                  <button
                    v-for="p in REJECT_PRESETS"
                    :key="p"
                    type="button"
                    class="task-preset"
                    @click="reasons[t.id] = p"
                  >
                    {{ p }}
                  </button>
                </div>
                <input
                  :id="`review-reason-${t.id}`"
                  v-model="reasons[t.id]"
                  type="text"
                  maxlength="512"
                  placeholder="写清依据"
                />
              </div>
              <div class="task-btns">
                <button
                  type="button"
                  class="task-btn task-btn--pass"
                  :disabled="busy[t.id]"
                  @click="approve(t)"
                >
                  通过
                </button>
                <button
                  type="button"
                  class="task-btn task-btn--reject"
                  :disabled="busy[t.id]"
                  @click="reject(t)"
                >
                  驳回
                </button>
              </div>
            </div>
          </li>
        </ul>

        <StateBlock
          v-if="!loading && list.length === 0"
          :variant="failed ? 'error' : 'empty'"
          :title="failed
            ? '审核队列没拉出来'
            : tab === 'review'
              ? '机审队列是空的'
              : '还没有机审处置记录'"
          :description="
            failed
              ? '可能是网络断了,点一下重试'
              : tab === 'review'
                ? '有命中敏感词的内容会自动进这里'
                : '审过的机审任务会留档在这里,驳回理由作者看得到'
          "
          :icon="failed ? WarningFilled : Files"
          :action-label="failed ? '重试' : '看看举报'"
          @action="failed ? onTabChange(tab) : onTabChange('report')"
        />
      </div>

      <!--
        无限滚动哨兵。放在滚动区**外面**:
        放在里面且列表为空时它一开始就在视口,IntersectionObserver
        会连发多次 load,第一页被重复追加。
        只挂机审两个 tab:report/processed 归 ReportPane 管(它有自己的哨兵)。
      -->
      <div v-if="tab === 'review' || tab === 'reviewHistory'" class="sentinel">
        <p v-if="loading && list.length" class="sentinel__text">正在加载更多</p>
        <p v-else-if="!hasMore && list.length" class="sentinel__text">到底了</p>
      </div>
    </template>

    <!-- 机审处置的撤销条。与举报域同一套形态,管理员学一次就够 -->
    <div v-if="undo" class="review-undo" role="status" aria-live="assertive">
      <span class="review-undo__text">{{ undo.text }}</span>
      <button type="button" class="review-undo__btn" @click="undoReview">撤销</button>
      <span class="review-undo__bar" :style="{ transform: `scaleX(${undo.ratio})` }" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ArrowLeft, Files, WarningFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessage } from '@/utils/feedback'
import { useReviewQueue } from '@/composables/useReviewQueue'
import { useReviewHotkeys } from '@/composables/useReviewHotkeys'
import { useListFocus } from '@/composables/useListFocus'
import { useIsMobile } from '@/composables/useBreakpoint'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import AdminDenied from '@/components/common/AdminDenied.vue'
import StateBlock from '@/components/common/StateBlock.vue'
import { reviewStatusLabel } from '@/constants/reviewStatus'

const router = useRouter()
const userStore = useUserStore()
const isMobile = useIsMobile()

const tab = ref('review')
const {
  list, pendingCount, hasMore, loading, failed, busy, reasons, undo,
  load, observeSentinel, approve, reject, undoReview,
} = useReviewQueue()

/**
 * 桌面快捷键(设计 §2「键盘」行,原文标为刚需)。
 *
 * 只挂在机审域 —— 举报域的交互形态是「列表点进去逐条处置」,
 * 且它有自己的 openOne/listMode 状态机;把 J/K 塞进那个状态机是另一件事,
 * 本轮不碰(避免两个状态机互相打架)。
 *
 * hint 走 ElMessage 而不是新开一个提示条:这个页面已经有撤销条占着底部,
 * 再加一条常驻提示会与它重叠。快捷键失效本身是低频事件,toast 足够。
 */
const { currentIndex } = useReviewHotkeys({
  list,
  loading,
  undo,
  busy,
  approve,
  reject,
  undoReview,
  onHint: (msg) => ElMessage.info(msg),
})

/**
 * 程序化聚焦当前卡片(设计 §5)。
 *
 * disabled 传 isMobile:移动端没有物理键盘,但读屏用户可能用外接键盘
 * 或开关控制访问 —— 在那类场景下抢走焦点反而会打断他当前的浏览位置。
 * 桌面端才做。
 */
const { setRef: setCardRef } = useListFocus({
  index: currentIndex,
  list,
  disabled: isMobile,
})

/**
 * 权限不足(AC-20)。
 *
 * 判定用 store 的 isAdmin getter,不等 API 返 403 —— 等 403 意味着用户
 * 已经看完一屏他不该看见的内容骨架。
 *
 * `ready` 与 `denied` 必须分开:userInfo 为 null(还没拉到)时**不**判 denied,
 * 否则会把加载中误判成无权限。
 */
const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

/**
 * 审核状态文案（管理员视角）。
 *
 * 来自 constants/reviewStatus.js —— 码 1/2 与作者端「我的审核」那块同源，
 * 只有码 0 是「待审」而非「审核中」（视角不同，刻意为之）。
 */
function statusText(s) {
  return reviewStatusLabel(s, true)
}

function aiVerdictText(v) {
  return { approve: '建议通过', reject: '建议驳回', manual: '转人工' }[v] || '未知'
}

/** 机审驳回常用理由（2026-10-28）：点一下填进输入框，仍可修改 */
const REJECT_PRESETS = ['含敏感词', '疑似广告引流', '色情低俗', '人身攻击', '与校园无关']

/** 切 Tab。两个机审视图各自重新拉第一页（举报域已拆去 AdminReports） */
let booted = false
function onTabChange(name) {
  tab.value = name
  booted = true
  load(name, true).then(() => observeSentinel(name))
}

/**
 * 首次进入必须自己拉一次（2026-10-28 修）。
 *
 * <p>此前只有 tab-change 触发 load，而 tab 初值就是当前 tab，用户不点就
 * 没有事件 —— 实测进入 /admin/review 是一片空白，队列有待审时管理员
 * 会以为没活干。booted 守住 el-tabs 若在挂载时补发 tab-change 的重复加载。
 */
onMounted(() => {
  if (!booted) onTabChange(tab.value)
})

/**
 * 标题下那行提示（2026-10-24 补第三档）。
 * 原先是二元的 `tab === 'report' ? '举报有时效,先处理' : '机审可以等'`，
 * 于是「已处理」页顶着「机审可以等」——一句话说的是另一个域的事。
 */
const tabHint = computed(() => {
  if (tab.value === 'reviewHistory') return '机审处置的留档，驳过的帖子为什么驳都在这里'
  return '机审可以等'
})

watch(
  () => userStore.isAdmin,
  (v) => {
    // 权限在会话中途变化(被降权):立刻离开本页,别让越权数据留在屏幕上
    if (v === false) router.replace('/')
  },
)
</script>

<style scoped src="./AdminReview.css"></style>
