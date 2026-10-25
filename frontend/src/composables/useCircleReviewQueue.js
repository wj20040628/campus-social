/**
 * 圈主协审队列（2026-10-24 新增，配 ReviewServiceImpl 的双队列分流）。
 *
 * [机制] GET /review/pending 按登录身份分流:圈主拿到的是「自己圈内、
 * 未超过升级窗(默认 24h)」的待审任务;超窗任务自动滑入平台管理员队列,
 * 圈主侧自然看不到。approve/reject 与管理员同一对端点,权限由服务端判定。
 *
 * [为什么不用 5 秒撤销条] 协审是低频动作(一个圈一天通常 0~2 条),
 * 撤销条的价值在高频批量处置;这里用直发 + 行内错误提示,少一层状态。
 *
 * [驳回为什么必须写理由] 与平台审核台同一约束:作者收到的驳回通知里
 * 只有这一句话,空理由等于让作者盲猜。
 */
import { ref, reactive } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { pending, approve, reject } from '@/api/review'

export function useCircleReviewQueue() {
  const list = ref([])
  const total = ref(0)
  const loading = ref(false)
  const failed = ref(false)
  /** 每条任务独立的提交中标记,{ [taskId]: true } */
  const busy = reactive({})
  /** 驳回理由输入,{ [taskId]: string } */
  const reasons = reactive({})

  async function load() {
    loading.value = true
    failed.value = false
    try {
      const data = await pending(null, 20)
      list.value = data.list || []
      // total 是服务端按身份算的全量待审数(圈主口径),抽屉标题角标用
      total.value = typeof data.total === 'number' ? data.total : list.value.length
    } catch {
      // 拉不到就静默置空:协审入口在圈管理抽屉里,不该因它打不开抽屉
      failed.value = true
      list.value = []
      total.value = 0
    } finally {
      loading.value = false
    }
  }

  async function approveOne(t) {
    if (busy[t.id]) return
    busy[t.id] = true
    try {
      await approve(t.id)
      list.value = list.value.filter((x) => x.id !== t.id)
      total.value = Math.max(0, total.value - 1)
      ElMessage.success('已通过,帖子对圈内可见')
    } catch {
      // 422/403 等错误 request.js 已弹 toast;此处拉一次列表对齐服务端真实状态
      await load()
    } finally {
      busy[t.id] = false
    }
  }

  async function rejectOne(t) {
    if (busy[t.id]) return
    const reason = (reasons[t.id] || '').trim()
    if (!reason) {
      ElMessage.warning('驳回要写理由,作者会看到')
      return
    }
    busy[t.id] = true
    try {
      await reject(t.id, reason)
      list.value = list.value.filter((x) => x.id !== t.id)
      total.value = Math.max(0, total.value - 1)
      ElMessage.success('已驳回,理由已通知作者')
    } catch {
      await load()
    } finally {
      busy[t.id] = false
    }
  }

  return { list, total, loading, failed, busy, reasons, load, approveOne, rejectOne }
}
