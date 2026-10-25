/**
 * 5 秒撤销窗口(AdminReview.vue.md §1.4 第 3 条 / §4)。
 *
 * [为什么需要]
 * 处置是**不可逆**的:封禁一个用户、删掉一条帖子,事后要靠管理员手工改回来。
 * 手机上误触的成本又特别高 —— 拇指落在屏幕上,三个并排按钮挨得很近。
 * 设计要求:任何处置成功后,用户在 5 秒内能反悔,且**结果与没点过完全一致**。
 *
 * [为什么延迟落库而不是先发再改判]
 * 两种实现:
 *   A 延迟 5 秒才发 handle —— 零后端改动,但管理员杀页面则处置丢失
 *   B 立即发 handle,撤销时再发一次改判 —— 不丢,但产生**两条 audit_log**
 * 选 A。理由:管理员一天点几百次,B 方案下审计日志会被撤销记录淹没,
 * 真出事时反而找不到那条真处置。代价已在回传 advisory 里标明。
 *
 * [交互要点]
 * 进度条用 transform: scaleX 而不是 width —— width 每帧触发重排,
 * 一条 60fps 的动画能在列表滚动时把整页拖到 30fps。只动 transform/opacity。
 */
import { onBeforeUnmount, ref } from 'vue'

/** 窗口时长。设计 §4 定死 5000ms */
export const UNDO_MS = 5000
/** 收缩节奏,16ms ≈ 60fps */
const TICK_MS = 16

export function useUndoWindow() {
  /** null 或 { text, ratio, meta } */
  const undo = ref(null)
  let timer = null
  /** 当前窗口待落库的回调。clear() 会清 undo 但保留它，供 flush 用 */
  let pendingCommit = null

  function clear() {
    if (timer) clearInterval(timer)
    timer = null
    undo.value = null
    pendingCommit = null
  }

  /**
   * 立刻执行当前窗口的 commit（不弹任何提示）。
   * open() 在已有未落库处置时调用它，而不是像原实现那样直接丢弃。
   */
  function firePending() {
    const fire = pendingCommit
    clear()
    if (fire) fire()
  }

  /**
   * 开一个撤销窗口。`commit` 在窗口耗尽时才被调用。
   *
   * @param {string} text 撤销条上的那句话,必须是**已完成的事实**而不是「处理中」
   * @param {() => Promise<void>|void} commit 真正落库的回调
   * @param {*} [meta] 撤销时要还回去的东西(被移除的那条记录)。
   *   撤销要「结果与没点过一致」,就得能把那条记录放回原位;
   *   不传 meta 就只能撤销请求、恢复不了列表,那不叫撤销。
   */
  function open(text, commit, meta = null) {
    /*
     * 【2026-10-24 修：先落库上一个窗口，再开新窗口】
     * 原实现 open() 开头调 clear()——它把 pendingCommit 直接置空，
     * 上一个还没落库的处置被**静默丢弃**：机审队列里 5 秒内连批两条，
     * 第一条的 API 调用永远不会发出，刷新后那条又回到队列，
     * 而管理员看到的是「已通过」的撤销条闪过，以为处置成功了。
     * 正确语义：第二个动作开始时，第一个动作立即落库
     * （他已经点了下一个，5 秒反悔期对上一条而言已经用完了）。
     */
    if (pendingCommit) firePending()
    const started = Date.now()
    const state = { text, ratio: 1, meta }
    undo.value = state
    pendingCommit = commit
    timer = setInterval(() => {
      const left = UNDO_MS - (Date.now() - started)
      state.ratio = Math.max(0, left / UNDO_MS)
      if (left <= 0) {
        firePending()
      }
    }, TICK_MS)
  }

  /**
   * 撤销。**不发任何请求** —— 窗口内请求根本没出去,
   * 所以「撤销」不需要补偿逻辑,这是选延迟落库方案最大的好处。
   *
   * @returns {*} 传进 open 的 meta,供调用方把记录放回原位
   */
  function undoNow() {
    const meta = undo.value?.meta ?? null
    clear()
    return meta
  }

  /**
   * 立刻落库（2026-10-24 加）。
   *
   * 【为什么必须有】原先只有 onBeforeUnmount(clear)：管理员点完处置、
   * 在 5 秒窗口内刷新页面或切走路由，定时器被清掉、commit 永远不执行 ——
   * 处置**静默丢失**，刷新后那条举报又回到待处理列表。
   * 代码注释里承认过这个代价（「管理员杀页面则处置丢失」），
   * 但代价不该由「以为已经处置完了」的管理员来承担。
   *
   * 与 undoNow 的区别：撤销是「当作没点过」，flush 是「点过了，立刻生效」。
   */
  function flush() {
    if (!undo.value) return
    const fire = pendingCommit
    clear()
    if (fire) fire()
  }

  /** 是否还有未落库的处置 */
  function hasPending() {
    return !!undo.value
  }

  /*
   * 离开组件时**落库**而不是丢弃。
   *
   * 这里覆盖 SPA 内的路由切换（window 不会卸载，但组件会）。
   * 浏览器刷新/关闭走下面的 pagehide —— Vue 的 unmount 在那种情况下
   * 不保证触发，只靠它仍会丢。
   */
  onBeforeUnmount(flush)

  /*
   * 刷新/关标签页时兜底。
   *
   * pagehide 比 beforeunload 可靠（iOS Safari 上 beforeunload 常不触发），
   * 两个都挂上，重复触发由 hasPending 天然去重（flush 后 undo 已清空）。
   */
  if (typeof window !== 'undefined') {
    const onLeave = () => {
      if (hasPending()) flush()
    }
    window.addEventListener('pagehide', onLeave)
    window.addEventListener('beforeunload', onLeave)
    onBeforeUnmount(() => {
      window.removeEventListener('pagehide', onLeave)
      window.removeEventListener('beforeunload', onLeave)
    })
  }

  return { undo, open, clear, undoNow, flush, hasPending }
}
