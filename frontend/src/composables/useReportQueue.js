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
import { computed, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { adminReports, handleReport } from '@/api/review'
import { REASON_TEXT as REASON_TEXT_SRC, reasonLabel } from '@/constants/reportReason'
import { useUndoWindow } from '@/composables/useUndoWindow'
import { useAdminPending } from '@/composables/useAdminPending'

/** 封禁时长。0 = 永久,放最后并单独标注 */
export const BAN_DAY_OPTIONS = [
  { v: 1, t: '1 天' },
  { v: 7, t: '7 天' },
  { v: 30, t: '30 天' },
  { v: 0, t: '永久' },
]

/**
 * 举报理由文案统一走 constants/reportReason.js，本文件不再自己维护一份。
 *
 * 【为什么删掉本地那份】原先这里另写了一张 REASON_TEXT 表，与用户端
 * ReportSheet 的选项漂移了：本地写成「2不实信息 3广告营销 4违规内容」，
 * 而契约与用户端都是「2广告 3色情 4诈骗」。后果是用户举报「色情」（码 3），
 * 管理员屏幕上显示「广告营销」—— 管理员据此决定处置方向，标签错了依据就错了。
 * 这类不一致最难查：两边各自都自洽，只有交叉验证才暴露。
 * 现在只有一处定义，结构上不会再分叉。
 */

/**
 * @param {import('vue').Ref<boolean>} handled false=待处理 / true=已处理
 *
 * 用 handled 而不是 status 区分两个列表：忽略按契约不改 status（仍为 0），
 * 只写 handler_id，所以 status 既过滤不掉已忽略的、也表达不出它。
 */
export function useReportQueue(handled) {
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

  /**
   * 游标分页（2026-10-24 补）。
   *
   * 【为什么不带分页是事故】后端 /admin/reports 本来就是游标分页
   * （pendingCount 注释明确写着「全量待审数，翻到第 3 页红点显示 0 会让
   * 管理员以为活干完了」），而前端此前只拉一页 20 条、没有加载更多：
   * 待审 35 条时剩下 15 条不可见，处理完可见的 20 条后页面显示
   * 「没有待处理的举报」——后端防了半天的坑被前端原样踩回去。
   */
  const cursor = ref(null)
  const hasMore = ref(false)

  const { undo, open, undoNow } = useUndoWindow()

  /**
   * 已处置数 = 总数 - 待处置数。
   *
   * 【口径警告（2026-10-24 修）】这个减法**只在 hasMore=false 时成立**：
   * 列表还有下一页时，「总数 - 已加载」里混着「没拉下来的待处置」，
   * 拿它当已处置数会把没看到的活算成干完的活。因此进度文案由
   * ReportPane 按 hasMore 分叉显示，doneCount 只在加载完时使用。
   */
  const doneCount = computed(() =>
    hasMore.value ? 0 : Math.max(0, total.value - list.value.length),
  )
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
    return reasonLabel(t)
  }

  /**
   * 拉列表。
   *
   * @param {boolean} reset true = 回第一页（换列表/处置失败重拉），
   *                        false = 无限滚动追加下一页
   */
  async function load(reset = true) {
    if (loading.value) return
    if (reset) {
      cursor.value = null
    }
    loading.value = true
    failed.value = false
    actionError.value = ''
    try {
      const data = await adminReports({ handled: handled.value, lastId: cursor.value, size: 20 })
      const rows = data.list || []
      list.value = reset ? rows : [...list.value, ...rows]
      /*
       * pendingCount 是后端给的待审总数（全量，不是本页条数）。
       * 已处理列表拿到的是「已处置总数」，不能拿来算进度 —— 那个进度条
       * （已处置 / 总数）只在待处理列表里有意义。已处理态下 total 退化为
       * 已加载条数即可，反正进度条那里会按 handled 隐藏。
       */
      total.value = data.pendingCount ?? list.value.length
      hasMore.value = data.hasMore === true
      cursor.value = hasMore.value ? (data.nextCursor ?? null) : null
      if (reset) {
        // 重拉后列表是全新的,旧下标没有意义(可能指向另一条)。
        // 处置失败会触发 load,不清的话管理员会看到「还在处置刚才那条」——
        // 实际上那条刚被别的管理员处置掉了。
        currentIndex.value = 0
        // 下标归零后必须退回列表模式:留在逐条模式会显示 list[0],
        // 而这次可能是处置失败的重拉 —— 管理员会以为「重试后自动换到下一条」,
        // 实际上屏幕内容根本没动,只有进度条悄悄回退。
        listMode.value = true
      }
    } catch {
      // 失败不清 list:已经看到的 20 条比一个空白页有用,只在完全没数据时才整块报错
      failed.value = true
    } finally {
      loading.value = false
    }
  }

  /** 无限滚动加载下一页。哨兵由 ReportPane 挂载并调用本方法 */
  function loadMore() {
    if (!hasMore.value || loading.value) return
    load(false)
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
      useAdminPending().refresh(true) // 待处置举报数变了,角标同步
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

  /*
   * 【2026-10-24 修：删掉 `watch(status, load)`】
   * status 在本文件从未定义 —— 浏览器里它解析到遗留全局 window.status
   * （string），Vue 对无效 watch source 只在开发态告警、运行时静默不生效，
   * 这个 watcher 从写下那天起就没有触发过一次。
   * 「handled 变化时重拉」的需求实际由 AdminReview 的 v-if/v-else-if 覆盖：
   * 两个 handled 值各自创建独立的 composable 实例，实例内 handled 不会变。
   * 与其留一个永远不触发的 watcher，不如删掉并在这里说清楚。
   */
  load()

  return {
    list,
    total,
    hasMore,
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
    loadMore,
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
