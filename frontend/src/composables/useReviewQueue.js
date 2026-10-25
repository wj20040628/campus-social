/**
 * 机审队列(AdminReview.vue.md §0「内容审核」域)。
 *
 * [为什么抽出来]
 * 本 composable 承担:游标分页 + 无限滚动 + 5 秒撤销的两次处置动作。
 * 这些逻辑与「举报处置」域零重叠(不同端点、不同动作集、不同错误码),
 * 但两者共享同一个 Tab 容器。留在 AdminReview.vue 里会让那个文件同时
 * 关心布局、分页、滚动观察、两套撤销 —— 每次改一处要读另外三处。
 *
 * [撤销的取舍见 useUndoWindow.js]
 * 通过/驳回都是一次点击 + 5 秒反悔,不再叠加 ElMessageBox.confirm:
 * 手机上「点通过 → 弹窗 → 再点确认」等于三步,管理员一天点几百次。
 */
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { pending as pendingApi, processed as processedApi, approve as approveApi, reject as rejectApi } from '@/api/review'
import { useUndoWindow } from '@/composables/useUndoWindow'
import { useAdminPending } from '@/composables/useAdminPending'

/** 单页条数。20 条在 375px 下是 3~4 屏,不至于让管理员滚到怀疑人生 */
const PAGE = 20

export function useReviewQueue() {
  const list = ref([])
  const pendingCount = ref(0)
  const cursor = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const failed = ref(false)
  const busy = reactive({})
  const reasons = reactive({})

  const { undo, open, undoNow } = useUndoWindow()

  let observer = null

  /**
   * @param {'review'|'processed'} which 待审还是已处理
   * @param {boolean} reset true = 换第一页,false = 追加
   */
  async function load(which, reset = false) {
    if (reset) {
      cursor.value = null
      list.value = []
      hasMore.value = true
    }
    loading.value = true
    try {
      const data = which === 'review'
        ? await pendingApi(cursor.value, PAGE)
        : await processedApi(cursor.value, PAGE)
      list.value = reset ? data.list : [...list.value, ...data.list]
      cursor.value = data.hasMore ? data.nextCursor : null
      hasMore.value = data.hasMore
      if (which === 'review') {
        /*
         * 角标口径（2026-10-24 修）：后端 CursorPage.total 现在是
         * 「status=0 的全量待审数」（ReviewServiceImpl.pending 补填）。
         * 原实现 `data.pendingTotal ?? data.list.length` 两头都错——
         * 字段名对不上、回退值又只是首页条数,于是角标恒为 ≤20,
         * 清完一页就归零,队列里还有几十条管理员却以为干完了。
         * total 拿不到时宁可显示条数,也不显示一个会误导的假 0。
         */
        pendingCount.value = typeof data.total === 'number' ? data.total : data.list.length
      }
    } catch {
      // request.js 已弹错,这里不重复提示。
      // 列表不清空:已经看到的 20 条比一个空白页有用,管理员还能手动重试
      failed.value = true
    } finally {
      loading.value = false
      if (reset) failed.value = false
    }
  }

  /**
   * 无限滚动观察哨兵。
   *
   * 哨兵必须在列表**外面**(AdminReview 模板里就是这么放的):
   * 放在滚动容器内且列表为空时,哨兵一开始就在视口里,
   * IntersectionObserver 会连发多次 load,第一页被重复追加。
   */
  function observeSentinel(which) {
    observer?.disconnect()
    if (which === 'report') return // 举报域没有分页
    // 等 DOM 更新完再找哨兵,否则 list 刚渲染时 .sentinel 还不存在
    requestAnimationFrame(() => {
      const el = document.querySelector('.sentinel')
      if (!el) return
      observer = new IntersectionObserver(
        (entries) => {
          if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load(which)
        },
        { threshold: 0.1, rootMargin: '200px' },
      )
      observer.observe(el)
    })
  }

  /** 驳回理由。设计要求驳回要写依据 —— 发布者会看到,不能默认空 */
  function approve(t) {
    if (busy[t.id]) return
    /*
     * 终态守卫（2026-10-24 补）：机审记录 tab 复用同一套快捷键,
     * A/R 落在已处理条目上时会打 approve/reject 接口——服务端 CAS 能拦住
     * (422「该任务已处理」),但那是把可预判的错误留给服务器。
     * 在数据层早退,历史 tab 里按键就只是无动作。
     */
    if (t.status !== 0) {
      ElMessage.info('这条已经处理过了')
      return
    }
    const id = t.id
    // 乐观出栈 + 开撤销条。管理员的眼睛要「点完就动」
    list.value = list.value.filter((x) => x.id !== id)
    open('已通过', async () => {
      busy[id] = true
      try {
        await approveApi(id)
        done()
      } finally {
        busy[id] = false
      }
    }, t)
  }

  function reject(t) {
    if (busy[t.id]) return
    if (t.status !== 0) {
      ElMessage.info('这条已经处理过了')
      return
    }
    const reason = (reasons[t.id] || '').trim()
    if (!reason) {
      ElMessage.warning('驳回要写理由，发布者会看到')
      return
    }
    const id = t.id
    list.value = list.value.filter((x) => x.id !== id)
    open('已驳回', async () => {
      busy[id] = true
      try {
        await rejectApi(id, reason)
        done()
      } finally {
        busy[id] = false
      }
    }, t)
  }

  function done() {
    pendingCount.value = Math.max(0, pendingCount.value - 1)
    // 队列真减一了,抽屉/侧栏的聚合角标同步刷新(2026-10-24 修:此前处置完角标不动)
    useAdminPending().refresh(true)
  }

  /**
   * 撤销机审处置。
   *
   * 和举报域不同:机审队列是**服务端给的列表**,撤销要把条目放回原位,
   * 否则管理员会以为自己撤销失败(条目不见了)。而分页列表无法可靠地
   * 插回原索引 —— 只能追加到头部并在文案上说清。
   */
  function undoReview(item) {
    const back = undoNow()
    if (!back) return
    list.value = [back, ...list.value]
    ElMessage.info('已撤销，这条回到列表顶部')
  }

  onBeforeUnmount(() => {
    observer?.disconnect()
  })

  return {
    list,
    pendingCount,
    hasMore,
    loading,
    failed,
    busy,
    reasons,
    undo,
    load,
    observeSentinel,
    approve,
    reject,
    undoReview,
  }
}
