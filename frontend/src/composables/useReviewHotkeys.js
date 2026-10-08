/**
 * 审核台的桌面快捷键(AdminReview.vue.md §2 桌面端「键盘」一行)。
 *
 * 设计原文:
 * > 键盘 | 无 | `J`/`K` 上下切换、`A` 通过、`R` 驳回、`U` 撤销。
 * > **桌面管理员是高频用户,快捷键是刚需**
 *
 * [为什么先要 currentIndex]
 * J/K 不是 A/R/U 的可选项,是它们的**前置**:approve / reject 都接收**条目对象**
 * (useReviewQueue.js: `approve(t)` 走 `t.id`),「对当前这条按 A」必须先知道
 * 当前是哪一条。所以在 hook 外层维护 currentIndex,由它反查条目。
 *
 * [为什么 currentIndex 要自愈]
 * useReviewQueue 的 approve / reject 会**把条目从 list 里移除**(乐观出栈)。
 * 如果 currentIndex 是纯数字,不跟着修正,就会出现:
 *   列表 3 条,currentIndex=2 → 按 A 处置第 3 条 → list 剩 2 条(索引 0,1)
 *   → currentIndex=2 已越界 → 再按 A 无响应、再按 J 也跳不过去
 * 所以每次处置后把索引**夹回有效范围**并按「移除后落在同一位」的语义对齐,
 * 这样连续按 A 能一路处理下去 —— 这正是高频管理员的使用方式。
 *
 * [护栏:为什么这三道都不能省]
 * 1. 输入框 / textarea / contenteditable → return。
 *    管理员在「驳回理由」框里打 `asdf` 会触发「A 通过」——
 *    这是**误审内容**的 bug,不是体验问题。
 * 2. ctrlKey / altKey / metaKey / shiftKey → return。
 *    否则 Cmd+J(开发者工具)、Cmd+K 等会误触发处置。
 * 3. 只在 ≥ lg 档注册。移动端没有物理键盘,且这几个字母在输入法
 *    候选词里,拦截会影响正常输入。
 *
 * 另外还拦掉:正在打字(any input 已覆盖)、正在加载 / 队列为空(无事可做),
 * 以及 alt+左箭头 这类浏览器默认行为相关的组合(已被修饰键护栏覆盖)。
 */
import { onMounted, onBeforeUnmount, ref, computed, watch } from 'vue'
import { useIsMobile } from '@/composables/useBreakpoint'

/** 会吞掉按键的元素。命中它们的祖先里最近的那个焦点元素 */
function isTypingTarget(el) {
  if (!el || el.nodeType !== 1) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  // contentEditable 常带这个属性值;空串也是可编辑态,别只判 `"true"`
  if (el.isContentEditable) return true
  return false
}

/**
 * @param {object} opts
 * @param {import('vue').Ref<Array>} opts.list          当前域的列表(机审队列)
 * @param {import('vue').Ref<boolean>} opts.loading     加载中:禁掉全部键
 * @param {import('vue').Ref<object|null>} opts.undo    撤销条当前状态
 * @param {object} [opts.busy]      useReviewQueue 的 busy 响应式表,{ id: true }
 * @param {(t:object)=>void} opts.approve   与列表里的按钮同一个函数
 * @param {(t:object)=>void} opts.reject
 * @param {()=>void} opts.undoReview
 * @param {()=>void} [opts.onHint] 可选:提示为什么键没生效(如「驳回要写理由」)
 * @param {(index:number, t:object|null)=>void} [opts.onMove]
 *   可选:J/K 成功切换后调用。举报域用它把列表模式切成逐条处置模式 ——
 *   J/K 的语义是「我要处置这一条」,所以顺手离开纯回看的列表模式。
 *   评审域不传(没有列表/逐条之分)。
 * @param {import('vue').Ref<boolean>} [opts.listMode]
 *   有列表模式的域传进来:列表模式下 current 为 null,不切过去的话按 A/R 会一直提示空。
 * @param {import('vue').Ref<number>} [opts.index]
 *   **外部下标**。域自己已经维护 currentIndex 时必须传进来(如举报域
 *   useReportQueue.currentIndex)—— 见下面「为什么必须由外部传进来」。
 *   要传 Ref(不是裸数字):`??` 只在 null / undefined 时回落,
 *   传裸 0 会被当成「没传」而静默自建,那恰好是本 bug 的形态。
 */
export function useReviewHotkeys({
  list, loading, undo, busy, approve, reject, undoReview, onHint, onMove, listMode, index,
}) {
  const isMobile = useIsMobile()

  /**
   * 当前选中条目的下标。
   *
   * [为什么必须能由外部传进来]
   * 最开始这里无条件 `ref(0)` 自建一份,机审域没问题(它没有自己的下标)。
   * 但举报域的 `current` 是由 useReportQueue 的 currentIndex 驱动的,
   * 两份下标各自变动 -> 界面显示第 2 条、实际处置第 1 条。
   * 这类错不报错、不掉帧,管理员只会觉得「这软件偶尔反应慢」,
   * 而实际后果是处置了不该处置的举报。所以有外部下标就一定用它。
   *
   * 暴露出去是为了让页面能标出「当前这条」(设计 §5 要求切换时程序化聚焦,
   * 那是渲染侧的活,这里只管状态)。
   *
   * [这里用 ?? 而不是 || —— 但理由要说准]
   * team-lead 给的理由是「若有人传 ref(null),|| 会静默回落」。**实测不成立**:
   * `ref()` 无论装什么值都返回对象,对象永远真值,所以 || 与 ?? 在
   * `ref(0)` / `ref(null)` 下行为完全一致,都会用外部那个。
   * 我把这一条核实后如实记在这里,免得留下一个错误的理由。
   *
   * 两者真正有差别的是**原始值**:传 `0` 时 || 回落到 ownIndex,?? 保留 0。
   * 那正是本 bug 的形态 —— 界面停在一个下标、逻辑用另一个。
   * 现在的调用方传的是 ref,碰不到;但判据的边界应该是显式的,
   * 而不是「靠传的对象恰好非空」。`??` 表达的是「只在调用方没给时才自建」,
   * 这正是这个参数的本意。
   */
  const ownIndex = ref(0)
  const currentIndex = index ?? ownIndex

  /** 当前条目。列表为空、越界,或在纯列表模式下,都是 null */
  const current = computed(() => {
    // listMode 是可选的:只有举报域有「列表 / 逐条」两态。
    // 列表模式下 current 语义上不存在,让调用方拿到 null 而不是 list[0]。
    if (listMode?.value) return null
    return list.value[currentIndex.value] ?? null
  })

  /**
   * 处置后列表会少一条,把索引夹回有效范围。
   *
   * 「落在同一位」而不是「夹到末尾」:管理员在一路按 A 时,
   * 期望的是下一条内容补上来,而不是回到队尾重新开始。
   */
  function clampIndex() {
    const max = list.value.length - 1
    if (max < 0) {
      currentIndex.value = 0
      return
    }
    if (currentIndex.value > max) currentIndex.value = max
    if (currentIndex.value < 0) currentIndex.value = 0
  }

  /**
   * 列表变化后夹回有效范围。
   *
   * **不用 flush:'sync'**:同步 watch 会在 approve 刚把条目从数组里
   * filter 掉的那一刻就触发 —— 那一刻当前条目已经不在 list 里了,
   * `current` 会短暂返回 null。表现是:按 A 的瞬间 `current` 闪一下空,
   * 若此时再按 A 就会被 requireCurrent 拦下并提示「队列是空的」,
   * 而队列明明还有 20 条。
   *
   * 默认 flush('pre')让它在渲染前的微任务里跑,此时数组已更新完但 DOM 还没动,
   * current 已经指向正确的下一条。
   */
  watch(list, () => clampIndex())

  /** 越界时不静默失败:告诉用户为什么没反应 */
  function requireCurrent() {
    if (loading.value) {
      onHint?.('还在加载，等一下再按')
      return null
    }
    // 列表模式不是「队列为空」,是「还没选条目」。这两句混起来管理员会以为坏了
    if (listMode?.value) {
      onHint?.('先按 J 选一条，或者点列表里的「去处置」')
      return null
    }
    const t = current.value
    if (!t) {
      onHint?.('队列是空的，没有可处置的条目')
      return null
    }
    return t
  }

  function move(delta) {
    const max = list.value.length - 1
    if (max < 0) {
      onHint?.('队列是空的')
      return
    }
    // 列表模式下 currentIndex 是个「还没被使用」的值(恒 0),不是「正停在第 1 条」。
    // 直接 +1 会让管理员第一次按 J 就落到第 2 条,第 1 条被跳过 —— 而且没有任何提示。
    // 所以列表模式把起点当成 -1(列表之上),J 落到第 1 条,K 无路可退。
    const from = listMode?.value ? -1 : currentIndex.value
    const next = from + delta
    // 到底/到顶都不静默:给一句提示,管理员才知道「刚才那下没生效」
    // 而不是在同一个条目上反复按 A(那会连着审同一条)。
    if (next > max) {
      onHint?.('已经是最后一条了')
      return
    }
    if (next < 0) {
      onHint?.('已经是第一条了')
      return
    }
    currentIndex.value = next
    // 切到哪一条就立刻让页面知道:J/K 的语义是「我要处置这一条」,
    // 停在纯回看的列表模式里再要按 A 才是多余的一步
    onMove?.(next, list.value[next] ?? null)
  }

  function onKeydown(e) {
    // 护栏 3:移动端不注册。放在函数内而不是 addEventListener 里判断,
    // 是为了让 resize 后跨断点也能立即生效(不必重挂监听)。
    if (isMobile.value) return

    // 护栏 2:任何修饰键组合都不响应。
    // **不拦 shift**:设计师要求的是 J/K/A/R/U 这五个键,用户按住 Shift 打出
    // 大写 J 一样是「下一条」的意图,而 e.key.toLowerCase() 已经把大小写归一了。
    // 拦掉 shift 只会让「我按了 Shift+J 没反应」,反而是误伤。
    if (e.ctrlKey || e.metaKey || e.altKey) return

    // 护栏 1:正在打字就不响应。
    if (isTypingTarget(e.target)) return

    const k = e.key.toLowerCase()

    if (k === 'j') {
      e.preventDefault()
      move(1)
      return
    }
    if (k === 'k') {
      e.preventDefault()
      move(-1)
      return
    }

    if (k === 'a') {
      const t = requireCurrent()
      if (!t) return
      // busy 中的条目不处置:与按钮的 :disabled 同语义
      if (busyOf(t)) {
        onHint?.('这一条正在处理中')
        return
      }
      e.preventDefault()
      approve(t)
      return
    }

    if (k === 'r') {
      const t = requireCurrent()
      if (!t) return
      if (busyOf(t)) {
        onHint?.('这一条正在处理中')
        return
      }
      e.preventDefault()
      reject(t)
      return
    }

    if (k === 'u') {
      // 没有撤销窗口时按 U 不该有任何反应,也不该拦截按键
      if (!undo.value) return
      e.preventDefault()
      undoReview()
    }
  }

  /**
   * busy 判定。与列表里按钮的 `:disabled="busy[t.id]"` 同语义 ——
   * 键盘不能比鼠标宽松,否则管理员能绕过按钮的禁用态。
   *
   * busy 没传时当作「不忙」:误拦会挡住合法操作,比不拦更糟。
   */
  function busyOf(t) {
    if (!busy || !t) return false
    return !!busy[t.id]
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

  return { currentIndex, current, clampIndex, move }
}