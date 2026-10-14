import { ElMessage, ElMessageBox } from '@/utils/feedback'
import * as mkt from '@/api/mkt'
import dayjs from 'dayjs'

/**
 * 「我的发布」卡片的属主操作(编辑 / 开关 / 满员 / 重激活 / 删除)。
 * ------------------------------------------------------------------
 * 从 MktPlaza 抽出来:这些动作自带确认弹窗、请求与失败语义,
 * 留在视图里会把文件推过行数门禁;而且它们与广场/列表的读取无关。
 *
 * 约定:每个动作成功后调用 `onChanged`(页面传 reloadMy)刷新「我的发布」。
 * 失败只弹 toast(request.js 已全局弹错,这里不再重复)。
 */
export function useMktOwnerActions(reloadMy) {
  /** 编辑:跳到发布页带 id,复用 MktPublish 表单(编辑模式)。 */
  function edit(activity, router) {
    router.push(`/mkt/publish?id=${activity.id}`)
  }

  /** 开/关。按当前 isEnable 反向操作。 */
  async function toggle(activity) {
    const en = Number(activity.isEnable) === 1
    try {
      if (en) await mkt.mktPause(activity.id)
      else await mkt.mktEnable(activity.id)
      ElMessage.success(en ? '已暂停' : '已开启')
      await reloadMy()
    } catch {
      /* request.js 已弹错 */
    }
  }

  /** 手动满员标记开关。 */
  async function full(activity) {
    const nowFull = Number(activity.isFull) === 1
    try {
      await mkt.mktFull(activity.id, !nowFull)
      ElMessage.success(nowFull ? '已取消满员' : '已标记满员')
      await reloadMy()
    } catch {
      /* request.js 已弹错 */
    }
  }

  /**
   * 重新激活:选新的投放截止时间(now < t <= now+12h)。
   * 后端二次校验时长上限与 24h/3 次限流;超限后端 429/400。
   */
  async function reactivate(activity) {
    const maxH = 12
    const defaultHours = 6
    try {
      const { value } = await ElMessageBox.prompt(
        `为「${activity.title}」重新投放 ${maxH} 小时内的哪个截止时间?(默认 6 小时后)`,
        '重新激活',
        {
          confirmButtonText: '激活',
          cancelButtonText: '取消',
          inputType: 'number',
          inputPlaceholder: '投放时长(小时,1–12)',
          inputPattern: /^[1-9]\d?$/,
          inputErrorMessage: '小时数填 1–12 之间的整数',
          inputValue: String(defaultHours),
        },
      )
      const hours = Number(value)
      if (!Number.isFinite(hours) || hours < 1 || hours > maxH) {
        ElMessage.warning('投放时长需在 1–12 小时之间')
        return
      }
      const newExpire = dayjs().add(hours, 'hour').format('YYYY-MM-DDTHH:mm:ss')
      await mkt.mktReactivate(activity.id, newExpire)
      ElMessage.success('已重新激活')
      await reloadMy()
    } catch (e) {
      // 用户取消:不提示。后端限流/校验失败:request.js 已弹错,这里兜底
      if (e !== 'cancel' && e && !(e instanceof Error)) {
        // request.js reject 的是带 message 的 Error 或业务文案
      }
    }
  }

  /** 软删:确认后删,删完刷新。 */
  async function remove(activity) {
    try {
      await ElMessageBox.confirm(`确定删除「${activity.title}」?删除后广场不再展示。`, '删除活动', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      })
    } catch {
      return // 取消
    }
    try {
      await mkt.deleteMkt(activity.id)
      ElMessage.success('已删除')
      await reloadMy()
    } catch {
      /* request.js 已弹错 */
    }
  }

  return { edit, toggle, full, reactivate, remove }
}

export default useMktOwnerActions
