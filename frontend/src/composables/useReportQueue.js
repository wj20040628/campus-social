/**
 * 举报处置的数据与动作(AdminReview.vue.md §1.3 / §1.4)。
 *
 * [为什么抽出来]
 * ReportPane.vue 的模板有 160 行(五态 + 吸底栏 + 撤销条),
 * 逻辑再写在里面就破 300 行。视图与数据拆开还有一个好处:
 * 「处置一个举报」这件事的完整语义 —— 乐观出栈、5 秒窗口、409 判定、
 * 撤销回位 —— 在一个文件里能读完,不用在模板和数据之间来回跳。
 *
 * [关键决策:先出栈后落库]
 * 见 useUndoWindow.js 的取舍说明。一句话:撤销要「结果与没点过完全一致」,
 * 延迟落库时撤销不需要任何补偿逻辑,立即落库则要写反向操作且会污染审计日志。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { adminReports, handleReport } from '@/api/review'
import { useUndoWindow } from '@/composables/useUndoWindow'

/** 封禁时长。0 = 永久,放最后并单独标注 */
export const BAN_DAY_OPTIONS = [
  { v: 1, t: '1 天' },
  { v: 7, t: '7 天' },
  { v: 30, t: '30 天' },
  { v: 0, t: '永久' },
]

/** reasonType 枚举 → 文案。契约 ReportVO.reasonType */
const REASON_TEXT = {
  1: '骚扰',
  2: '不实信息',
  3: '广告营销',
  4: '违规内容',
  5: '其他',
}

export function useReportQueue(status) {
  const list = ref([])
  const total = ref(0)
  const loading = ref(false)
  const failed = ref(false)
  const busy = ref(false)
  const actionError = ref('')
  const listMode = ref(true)
  const expanded = ref(false)
  const banDays = ref(7)
  /** 'idle' 一行三按钮; 'ban' 已展开封禁时长选择 */
  const stage = ref('idle')

  const { undo, open, undoNow } = useUndoWindow()

  /** 已处置数 = 总数 - 待处置数。进度条靠它,不能靠 list.length */
  const doneCount = computed(() => Math.max(0, total.value - list.value.length))
  const progressPct = computed(() => (total.value ? (doneCount.value / total.value) * 100 : 0))
  const current = computed(() => (listMode.value ? null : list.value[0] || null))
  /** 摘要超 8 行(约 60 字)才给「展开全文」,否则给一个点不动的按钮是噪音 */
  const longContent = computed(() => (current.value?.targetSummary?.length || 0) > 60)
  const banLabel = computed(() => {
    if (stage.value !== 'ban') return '成立并封禁'
    return banDays.value === 0 ? '永久封禁' : `封禁 ${banDays.value} 天`
  })

  function reasonText(t) {
    return REASON_TEXT[t] || '其他'
  }

  async function load() {
    loading.value = true
    failed.value = false
    actionError.value = ''
    try {
      const data = await adminReports({ status: status.value })
      list.value = data.list || []
      // pendingCount 是后端给的待审总数;非待审状态下可能不给,退化为本页条数
      total.value = data.pendingCount ?? list.value.length
      if (!list.value.length) listMode.value = true
    } catch {
      // 失败不清 list:已经看到的 20 条比一个空白页有用,只在完全没数据时才整块报错
      failed.value = true
    } finally {
      loading.value = false
    }
  }

  /** 列表模式点某条 → 进逐条处置。只挪指针,不重新请求 */
  function openOne(r) {
    const i = list.value.findIndex((x) => x.id === r.id)
    if (i < 0) return
    list.value = [list.value[i], ...list.value.slice(0, i), ...list.value.slice(i + 1)]
    listMode.value = false
    reset()
  }

  function reset() {
    stage.value = 'idle'
    expanded.value = false
    actionError.value = ''
    banDays.value = 7
  }

  /** 第一下点「成立并封禁」只展开时长选择,不提交 —— 不可逆动作要多一道确认 */
  function confirmBan() {
    stage.value = 'ban'
    actionError.value = ''
  }

  /**
   * 执行处置。先乐观出栈 + 开撤销条,5 秒后才真发请求。
   * 顺序不能反:管理员的眼睛要「点完就动」,等 5 秒才跳下一条会像点击没生效。
   */
  function act(action) {
    if (busy.value) return
    const report = current.value
    if (!report) return

    const body = { action }
    if (action === 'ban') body.banDays = banDays.value

    list.value = list.value.slice(1)
    if (!list.value.length) listMode.value = true

    const text = {
      ban: banDays.value === 0 ? '已永久封禁该用户' : `已封禁 ${banDays.value} 天`,
      reject: '举报不成立，已驳回',
      ignore: '已忽略这条举报',
    }[action]

    open(text, () => commit(report.id, body), report)
  }

  async function commit(id, body) {
    busy.value = true
    try {
      await handleReport(id, body)
    } catch (e) {
      // 409 = 已被别人处置过。这不是网络失败,不该让管理员以为可以重试
      actionError.value = e?.response?.status === 409
        ? '这条举报刚被别的管理员处置过了'
        : '没处置成功，已经重新拉了一次列表'
      await load()
    } finally {
      busy.value = false
    }
  }

  /** 撤销:把条目放回列表头部。窗口内请求根本没发出去,所以不需要任何补偿 */
  function onUndo() {
    const back = undoNow()
    if (!back) return
    list.value = [back, ...list.value]
    listMode.value = false
    reset()
    ElMessage.info('已撤销，这条还在待处置里')
  }

  watch(status, load)
  load()

  return {
    list,
    total,
    loading,
    failed,
    busy,
    actionError,
    listMode,
    expanded,
    banDays,
    stage,
    undo,
    doneCount,
    progressPct,
    current,
    longContent,
    banLabel,
    reasonText,
    load,
    openOne,
    confirmBan,
    act,
    onUndo,
  }
}
