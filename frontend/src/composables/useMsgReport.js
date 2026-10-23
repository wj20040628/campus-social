import { computed, ref } from 'vue'

/**
 * 举报「某条聊天消息」的状态与提交（2026-10-21）。
 *
 * 从 ChatThread.vue 抽出：那边加了这段逻辑后到 440 行，超了 400 行门禁。
 * 抽出的另一个好处是判定口径只写一处 —— 原来 `canQuote` 和 `canReport`
 * 的条件完全一样（对方发的 + 未撤回），两处重复写迟早会漂移。
 *
 * 后端能力：POST /report 支持 targetType=4（聊天消息），
 * 举报成立时 ReportServiceImpl 会软删该消息（is_deleted=1），
 * 于是它从双方会话里消失 —— 这正是「举报骚扰私信」应有的效果。
 */
export function useMsgReport() {
  /** 举报表开合 */
  const reportOpen = ref(false)
  /** 被举报消息 id */
  const reportTargetId = ref(null)

  /**
   * 一条消息能否举报。
   *
   * 为什么排除 fromMe：举报自己发的消息没有意义（用户随时可撤回）。
   * 为什么排除 recalled：撤回后内容已不可见，管理员看到的是空文本，
   * 举报无法被判断真假 —— 这种举报只会污染队列。
   */
  const canReportOf = (msg) => !!msg && !msg.fromMe && !msg.recalled

  /** 打开举报表：先记目标再开层，顺序不能反（开层时 ReportSheet 要读 targetId） */
  function openReport(msg) {
    if (!msg?.id) return
    reportTargetId.value = msg.id
    reportOpen.value = true
  }

  return { reportOpen, reportTargetId, canReportOf, openReport }
}

/** 消息举报的 targetType（与 openapi 契约一致：4 = 聊天消息） */
export const MSG_REPORT_TARGET_TYPE = 4

export default { useMsgReport, MSG_REPORT_TARGET_TYPE }
