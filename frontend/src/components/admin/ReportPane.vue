<template>
  <!--
    举报处置(AdminReview.vue.md §0「举报处置」Tab + §1.3 卡片 + §1.4 吸底栏)

    为什么默认落在这个 Tab:OD-19 的时效压力来自举报,不是来自机审队列。
    机审任务可以等,举报人等着被回应。

    处置动作三选一(契约 ReportHandleRequest):
      ban    成立并封禁 -> banDays 必填(1/7/30,0=永久)
      reject 举报不成立
      ignore 忽略,仅记录处置人

    数据与动作全在 useReportQueue.js,本文件只负责视图与五态。
  -->
  <div class="report-pane">
    <!--
      快捷键说明。设计 §2 把 J/K/A/R/U 定为「桌面管理员的刚需」,但没给发现机制。
      放在吸底栏**上方**:与操作按钮同屏,管理员学键时眼睛不用来回扫。

      标签写全称不写单字:举报域的「A」在机审域是「通过」,在这里是「忽略」。
      只写「A 忽略」怕管理员带着机审的肌肉记忆来;写「A 忽略(不采纳)」最稳。
      「成立并封禁」不在列表里 —— 它没绑键,列出来会让人去找那个键。
    -->
    <p v-if="!isMobile && !listMode && !readOnly" class="report-pane__keys">
      <span><kbd>J</kbd><kbd>K</kbd> 切换</span>
      <span><kbd>A</kbd> 忽略（不采纳）</span>
      <span><kbd>R</kbd> 驳回</span>
      <span><kbd>U</kbd> 撤销</span>
    </p>

    <!--
      行 2:进度条。告诉管理员「还剩多少」,这是他继续点下去的动力。
      已处理态不显示：那里没有「待处置」这回事，进度条的分母是待审总数、
      分子却是已处理条数，画出来是个没有意义的比例。
    -->
    <div v-if="current && !readOnly" class="report-pane__progress">
      <div class="report-pane__progress-track">
        <span class="report-pane__progress-fill" :style="{ width: `${progressPct}%` }" />
      </div>
      <p class="report-pane__progress-text">
        第 {{ doneCount + 1 }} 条 / 共 {{ total }} 条待处置
      </p>
    </div>

    <!-- 行 3:可滚动内容区。只有这一区滚动,吸底栏不随之移动 -->
    <div class="report-pane__scroll">
      <!-- 加载骨架:管理员是等待成本最高的一类用户,骨架让他知道「在加载」而不是「卡死了」 -->
      <ul v-if="loading && list.length === 0" class="report-pane__skeletons" aria-hidden="true">
        <li v-for="n in 3" :key="n" class="report-skeleton">
          <span class="report-skeleton__line report-skeleton__line--tag" />
          <span class="report-skeleton__line report-skeleton__line--who" />
          <span class="report-skeleton__line" />
          <span class="report-skeleton__line" />
          <span class="report-skeleton__line report-skeleton__line--short" />
        </li>
      </ul>

      <!-- 行级错误:处置失败时浮在吸底栏上方,当前卡片不跳走 -->
      <p v-if="actionError" class="report-pane__action-error" role="alert">{{ actionError }}</p>

      <!--
        列表模式:折叠版,只用于回看与检索。
        设计明确「批量处置在手机上必然误触」,所以每条只给一个「去处置」入口。
      -->
      <ul v-else-if="listMode" class="report-pane__rows">
        <li v-for="r in list" :key="r.id" class="report-row">
          <button type="button" class="report-row__btn" @click="openOne(r)">
            <span class="report-row__head">
              <span class="report-row__reason">{{ reasonText(r.reasonType) }}</span>
              <span class="report-row__time">{{ fmt(r.createTime) }}</span>
            </span>
            <span class="report-row__summary">{{ r.targetSummary || '内容已被删除' }}</span>
          </button>
        </li>
      </ul>

      <!-- 逐条处置模式:当前一条的完整卡片 -->
      <article v-else-if="current" class="report-card">
        <div class="report-card__row">
          <span class="report-card__reason">{{ reasonText(current.reasonType) }}</span>
          <span class="report-card__time">{{ fmt(current.createTime) }}</span>
        </div>

        <p class="report-card__reporter">举报人:{{ current.reporterNickname || '匿名' }}</p>

        <hr class="report-card__rule" />

        <h3 class="report-card__label">被举报内容</h3>
        <p class="report-card__content" :class="{ 'is-open': expanded }">{{ current.targetSummary }}</p>
        <button
          v-if="longContent"
          type="button"
          class="report-card__more"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          {{ expanded ? '收起' : '展开全文' }}
        </button>

        <!-- 补充说明:举报人填的 reasonText,超 60 字后端已截断并置 truncated -->
        <div v-if="current.reasonText" class="report-card__note">
          <p class="report-card__note-text">
            {{ current.reasonText }}<span v-if="current.reasonTextTruncated">（后端已截断）</span>
          </p>
        </div>

        <!--
          处置结论（2026-10-24 加）：已处理态唯一能说明「这条后来怎么了」的地方。
          在此之前已处理列表根本不存在，管理员处置完就再也查不到自己做过什么。

          三种结论的判定：status=1 成立 / 2 驳回；而「忽略」按契约不改 status
          （仍为 0）只写 handler_id —— 所以 status=0 但 handlerId 非空 = 已忽略，
          这个分支不能少，否则忽略过的记录会被显示成「待处理」。
        -->
        <div v-if="readOnly" class="report-card__verdict">
          <p class="report-card__verdict-line">
            处置结论：<b>{{ verdictText(current) }}</b>
          </p>
          <p v-if="current.handleTime" class="report-card__verdict-line">
            处置时间：{{ fmt(current.handleTime) }}
          </p>
          <p v-if="current.handleNote" class="report-card__verdict-line">
            处置说明：{{ current.handleNote }}
          </p>
        </div>
      </article>

      <!--
        空态分两种,不能共用一句「暂无」:
          没数据 / 加载失败
        「现在没活干」和「页面坏了」混起来会让管理员以为系统正常,实际是拉取挂了(AC-19)。
      -->
      <StateBlock
        v-if="!loading && list.length === 0 && failed"
        variant="error"
        title="举报没拉出来"
        description="可能是网络断了,点一下重试"
        :icon="WarningFilled"
        action-label="重试"
        @action="load"
      />
      <StateBlock
        v-else-if="!loading && list.length === 0 && !failed"
        variant="empty"
        :title="readOnly ? '还没有处理过的举报' : '没有待处理的举报'"
        :description="readOnly ? '处置过的举报会留在这里，能查到谁在什么时候怎么处理的' : '校园里这会儿挺安静，这是好事'"
        :icon="Files"
        :action-label="readOnly ? '回到待处理' : '看看已处理的'"
        @action="emit('switch-tab', readOnly ? 'report' : 'processed')"
      />
    </div>

    <!--
      行 4:吸底操作栏(§1.4)。
      「成立并封禁」放最右是**刻意的**:右手握持拇指落在右下,
      把不可逆动作放在最容易误触的位置,是防误触而不是反人体工学。
    -->
    <footer v-if="current && !listMode && !readOnly" class="report-pane__foot">
      <!--
        封禁天数:默认 7 天。0(永久)不设默认 ——
        永久封禁是不可逆里最重的一档,必须让管理员主动去选,不能顺手点到。
      -->
      <div v-if="stage === 'ban'" class="report-pane__ban-days">
        <label for="rp-ban-days">封禁时长</label>
        <select id="rp-ban-days" v-model.number="banDays" class="report-pane__ban-select">
          <option v-for="d in BAN_DAY_OPTIONS" :key="d.v" :value="d.v">{{ d.t }}</option>
        </select>
        <p class="report-pane__ban-warn">封禁后不能发帖、不能搭话</p>
      </div>

      <div class="report-pane__actions">
        <button type="button" class="report-pane__btn" :disabled="busy" @click="act('ignore')">
          忽略
        </button>
        <!--
          驳回（2026-10-21 改成一步到底）。
          原来它写的是 `stage === 'ban' ? act('reject') : confirmBan()`，
          而 confirmBan() 只是把 stage 切到 'ban'（展开封禁时长选择）——
          于是管理员点「驳回」时屏幕会冒出一个「封禁时长」下拉，
          既不合语义（驳回不封禁任何人），又让人以为点了没反应。
          驳回是不可逆但**无副作用**的动作（不封人、不下架），不需要二段确认。
        -->
        <button
          type="button"
          class="report-pane__btn"
          :disabled="busy"
          @click="act('reject')"
        >
          驳回
        </button>
        <!--
          「成立并封禁」保留二段确认：它要选封禁天数，且会封人、下架内容，
          是全套操作里唯一不可撤销的重动作。第一下展开时长面板，
          第二下才真正执行 —— 这是它该有的摩擦。
        -->
        <button
          type="button"
          class="report-pane__btn report-pane__btn--danger"
          :disabled="busy"
          @click="stage === 'ban' ? act('ban') : confirmBan()"
        >
          {{ banLabel }}
        </button>
      </div>
    </footer>

    <!--
      撤销条(§1.4 第 3 条 / §4)。刻意不用 ElMessage:那个浮在顶部,
      手机单手握持时够不着。底边进度条线性收缩,比文字「5 秒后可撤销」直观。
    -->
    <div v-if="undo" class="report-undo" role="status" aria-live="assertive">
      <span class="report-undo__text">{{ undo.text }}</span>
      <button type="button" class="report-undo__btn" @click="onUndo">撤销</button>
      <span class="report-undo__bar" :style="{ transform: `scaleX(${undo.ratio})` }" />
    </div>
  </div>
</template>

<script setup>
/**
 * 举报处置面板。作为 AdminReview 的默认 Tab 挂载。
 *
 * 拆成独立组件而不是塞进 AdminReview.vue:AdminReview 要同时管顶栏、Tab、
 * 权限不足与机审队列,再加举报的三动作会破 300 行。
 * 两域共享 Tab 但数据源、动作集、错误语义完全不同,耦合起来每次改一边都要读另一边。
 */
import { computed, ref, toRef } from 'vue'
import dayjs from 'dayjs'
import { Files, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'
import { useReportQueue, BAN_DAY_OPTIONS } from '@/composables/useReportQueue'
import { useReviewHotkeys } from '@/composables/useReviewHotkeys'
import { useIsMobile } from '@/composables/useBreakpoint'
import StateBlock from '@/components/common/StateBlock.vue'

const props = defineProps({
  /** 0 待审 / 1 成立 / 2 驳回。handled=true 时忽略本字段 */
  status: { type: Number, default: 0 },
  /**
   * false=待处理（可处置），true=已处理（只读回看）。
   *
   * 【为什么不能只看 status】「忽略」按契约不改 status（仍是 0），
   * 只写 handler_id。所以待处理列表必须靠 handler_id 排除它，
   * 已处理列表也必须靠 handler_id 才能包含它 —— 单凭 status 两边都表达不了。
   */
  handled: { type: Boolean, default: false },
})

const emit = defineEmits(['switch-tab'])

const isMobile = useIsMobile()

/** 只看不改：已处理态不出现任何处置按钮 */
const readOnly = computed(() => props.handled)

const q = useReportQueue(toRef(props, 'handled'))
const {
  list, total, loading, failed, busy, actionError, listMode,
  expanded, banDays, stage, undo, doneCount, progressPct, current, currentIndex,
  longContent, banLabel,
  reasonText, load, openOne, confirmBan, act, onUndo, reset,
} = q

/**
 * busy 的适配。useReviewHotkeys 读 `busy[t.id]`(机审域是按 id 的表),
 * 而举报域只有一个布尔「当前这条正在提交」。
 *
 * 用 computed 而不是对象字面量:字面量每次渲染都是新对象,
 * `busy[t.id]` 恒为 undefined —— 护栏看着在、实际永远放行,
 * 于是「正在提交时又按一次 A」会重复提交。
 */
const busyById = computed(() => ({
  [current.value?.id ?? -1]: busy.value,
}))

/**
 * 桌面快捷键(设计 §2「键盘」行)。与机审域同一套机制(复用 useReviewHotkeys)。
 *
 * [键位与三动作的映射,以及为什么少一个]
 * 举报域是三动作:成立并封禁 / 驳回 / 忽略。设计给的键位是 A 通过、R 驳回、U 撤销。
 * 映射:
 *   A → ignore(忽略)  「忽略」在举报语境里就是「不采纳」,与机审的「通过」同义
 *   R → reject(驳回)  同名对应
 *   U → onUndo        同名对应
 *   **「成立并封禁」刻意不绑快捷键**。
 *   理由:它是三个动作里唯一不可逆的,而且封禁时长需要选(默认 7 天)。
 *   设计把它放在吸底栏最右是**刻意的防误触**——右手拇指就落在那儿。
 *   绑一个单键等于把这个防误触设计绕过去:管理员手放在键盘上敲 A 时
 *   相邻的 B/C 键随时可能敲错,一次误判就是封禁一个用户且要等管理员手动解封。
 *   宁可少一个键,不要把不可逆动作降级成单键。
 */
useReviewHotkeys({
  list,
  loading,
  undo,
  busy: busyById,
  // 把 useReportQueue 的下标交给快捷键用,不能让它自己再存一份 ——
  // 两份下标会各走各的,界面停在第 2 条而 act() 处置第 1 条,且不报错。
  index: currentIndex,
  /*
   * 已处理态下 A/R/U 一律不动作（2026-10-24 加）。
   * 不拦的话，管理员在只读列表里随手按一下 A 就会去打 handle 接口，
   * 拿回一个 409「该举报已被处置」——不危险，但屏幕上凭空冒出一句
   * 看不懂的报错，而管理员只是翻看历史记录而已。
   * （提示条本身也已按 readOnly 隐藏，这里再兜一道，因为键可能是盲按的。）
   */
  approve: () => {
    if (!readOnly.value) act('ignore')
  },
  reject: () => {
    if (!readOnly.value) act('reject')
  },
  undoReview: () => {
    if (!readOnly.value) onUndo()
  },
  listMode,
  // J/K 切到哪条就直接进逐条处置:J/K 的语义是「我要处置这一条」,
  // 停在纯回看的列表模式里再按 A 是多余的一步。
  // reset 不能省:切条目后上一条的「封禁时长选择 / 展开全文 / 错误提示」
  // 必须清掉,否则吸底栏还开着上一条选的天数。
  onMove: () => {
    listMode.value = false
    reset()
  },
  onHint: (msg) => {
    if (!readOnly.value) ElMessage.info(msg)
  },
})

function fmt(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

/**
 * 处置结论文案（2026-10-24 加）。
 *
 * 关键分支：status=0 但 handlerId 非空 = **已忽略**。
 * 「忽略」按契约不改 status，只看 status 会把它标成「待处理」——
 * 一条已被忽略的举报在已处理列表里写着「待处理」，比不显示更误导。
 */
function verdictText(r) {
  if (!r) return ''
  if (r.status === 1) return '成立并封禁'
  if (r.status === 2) return '驳回'
  return r.handlerId ? '忽略' : '待处理'
}
</script>

<style scoped src="./ReportPane.css"></style>
