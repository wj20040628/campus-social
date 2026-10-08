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

  function clear() {
    if (timer) clearInterval(timer)
    timer = null
    undo.value = null
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
    clear()
    const started = Date.now()
    const state = { text, ratio: 1, meta }
    undo.value = state
    timer = setInterval(() => {
      const left = UNDO_MS - (Date.now() - started)
      state.ratio = Math.max(0, left / UNDO_MS)
      if (left <= 0) {
        clear()
        // 不 await:窗口消失与请求发出解耦,请求失败走各自的错误处理
        commit()
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

  onBeforeUnmount(clear)

  return { undo, open, clear, undoNow }
}
