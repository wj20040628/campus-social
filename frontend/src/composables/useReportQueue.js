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

  /**
   * 当前处置条目的下标。
   *
   * [为什么从「重排到头部」改成 index]
   * 原实现把选中的那条**搬到数组头部**(`openOne` 重排、`act` 用 slice(1) 砍第一个),
   * 于是 `current` 恒等于 `list[0]`。那套写法能用,但接不了 J/K ——
   * useReviewHotkeys 假设的是「list + currentIndex」,两套机制会打架。
   * 统一成 index 驱动后,「当前是哪条」由一个数字表达,两边说的是同一件事。
   *
   * [顺带修掉的一个偏差]
   * 原 `onUndo` 用 `[back, ...list]` 把撤销的条目塞回**头部**,这会改变列表顺序,
   * 不符合设计 §1.4 第 3 条要求的「结果与没点过完全一致」。
   * 按下标插回原位才是真的没动过 —— 管理员第 3 条按了封禁又撤销,
   * 后面几条的相对顺序不该变。
   */
  const currentIndex = ref(0)
  const current = computed(() => (listMode.value ? null : list.value[currentIndex.value] || null))

  /** 列表变化后把下标夹回有效范围 */
  function clampIndex() {
    const max = list.value.length - 1
    if (max < 0) {
      currentIndex.value = 0
      return
    }
    if (currentIndex.value > max) currentIndex.value = max
    if (currentIndex.value < 0) currentIndex.value = 0
  }
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
      // 重拉后列表是全新的,旧下标没有意义(可能指向另一条)。
      // 处置失败会触发 load,不清的话管理员会看到「还在处置刚才那条」——
      // 实际上那条刚被别的管理员处置掉了。
      currentIndex.value = 0
      // 下标归零后必须退回列表模式:留在逐条模式会显示 list[0],
      // 而这次可能是处置失败的重拉 —— 管理员会以为「重试后自动换到下一条」,
      // 实际上屏幕内容根本没动,只有进度条悄悄回退。
      listMode.value = true
      if (!list.value.length) listMode.value = true
    } catch {
      // 失败不清 list:已经看到的 20 条比一个空白页有用,只在完全没数据时才整块报错
      failed.value = true
    } finally {
      loading.value = false
    }
  }

  /**
   * 列表模式点某条 → 进逐条处置。只挪指针,不重新请求。
   *
   * 不再重排数组 —— 只记下标。重排会让「撤销插回原位」变得难以推理,
   * 而 J/K 也需要一个稳定的下标语义。
   */
  function openOne(r) {
    const i = list.value.findIndex((x) => x.id === r.id)
    if (i < 0) return
    currentIndex.value = i
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

    // 按下标移除,不用 slice(0)/slice(1) —— 那是「砍第一个」的写法,
    // 建立在「当前项恒在头部」的旧假设上。J/K 切到第 3 条时它会砍错。
    const at = currentIndex.value
    list.value = list.value.filter((_, i) => i !== at)
    clampIndex()
    if (!list.value.length) listMode.value = true

    const text = {
      ban: banDays.value === 0 ? '已永久封禁该用户' : `已封禁 ${banDays.value} 天`,
      reject: '举报不成立，已驳回',
      ignore: '已忽略这条举报',
    }[action]

    open(text, () => commit(report.id, body), { report, at })
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

  /**
   * 撤销:把条目放回**原来的下标**。窗口内请求根本没发出去,所以不需要任何补偿。
   *
   * 放回原位而不是塞到头部:设计 §1.4 第 3 条要求「结果与没点过完全一致」。
   * 塞头部虽然让那条又可见了,但它后面的条目全被顶后一位 ——
   * 管理员第 3 条封禁又撤销,再往下处置时看到的顺序已经变了,不是「没点过」。
   */
  function onUndo() {
    const back = undoNow()
    if (!back) return
    const { report, at } = back
    const next = [...list.value]
    // 下标可能被 clamp 夹过(比如当时删的是最后一条),插回时再夹一次,
    // 否则会出现「插到数组之外」把条目 append 到末尾的静默错位。
    const pos = Math.min(Math.max(at ?? 0, 0), next.length)
    next.splice(pos, 0, report)
    list.value = next
    currentIndex.value = pos
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
    currentIndex,
    longContent,
    banLabel,
    reasonText,
    load,
    openOne,
    confirmBan,
    act,
    onUndo,
    // reset 要导出的原因:ReportPane 用 J/K 切条目时,切过去要清掉上一条的
    // 「封禁时长选择 / 展开全文 / 上一条的错误提示」,否则会出现
    // 「A 封禁第 1 条后按 J 到第 2 条,吸底栏还开着 7 天时长选择」——
    // 管理员以为在给第 2 条选时长,实际那个面板属于第 1 条。
    reset,
  }
}
