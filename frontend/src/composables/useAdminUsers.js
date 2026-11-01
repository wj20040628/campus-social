/**
 * 用户处置页的数据与动作(AdminUsers.vue,2026-10-24 新增)。
 *
 * [为什么有这个页] 此前封人只能走举报流程——管理员看到刷屏 spam 得先
 * 以用户身份举报、再去队列处置;解封更是没有任何端点,永久封禁误判后
 * 只能改数据库。本 composable 配 /admin/users 三个端点补齐:
 * 找人(昵称/登录名/学号模糊搜 + 状态筛) → 直接封禁 / 手动解封。
 */
import { ref } from 'vue'
import { adminUsers, banUser, unbanUser, muteUser, unmuteUser } from '@/api/admin'
import { ElMessage } from '@/utils/feedback'

/** 单页条数,与核验/机审队列一致 */
const PAGE = 20

/** 状态筛选项。null = 全部 */
export const STATUS_FILTERS = [
  { v: null, t: '全部' },
  { v: 0, t: '正常' },
  { v: 1, t: '封禁中' },
  // 'muted' 是独立维度：禁言中的账号 status 仍是 0，不能用 status 表达
  { v: 'muted', t: '禁言中' },
]

/** 封禁时长。0=永久放最后:最重的一档不能是顺手点的那个 */
export const BAN_DAYS = [
  { v: 1, t: '1 天' },
  { v: 7, t: '7 天' },
  { v: 30, t: '30 天' },
  { v: 0, t: '永久' },
]

/** 禁言时长。不提供永久——永久对应封禁，禁言只是限期措施 */
export const MUTE_DAYS = [
  { v: 1, t: '1 天' },
  { v: 3, t: '3 天' },
  { v: 7, t: '7 天' },
  { v: 30, t: '30 天' },
]

export function useAdminUsers() {
  const list = ref([])
  const total = ref(0)
  const cursor = ref(null)
  const hasMore = ref(false)
  const loading = ref(false)
  const failed = ref(false)
  const keyword = ref('')
  const statusFilter = ref(null)

  /* 封禁面板(行内展开,不用弹窗——理由输入框在弹窗里会被键盘顶出视口) */
  const banning = ref(null)
  const banDays = ref(7)
  const banReason = ref('')
  /* 禁言面板与封禁同构:同一时刻只允许一行展开一个面板 */
  const muting = ref(null)
  const muteDays = ref(7)
  const muteReason = ref('')
  const busy = ref(false)
  const actionError = ref('')

  async function load(reset = true) {
    if (loading.value) return
    if (reset) cursor.value = null
    loading.value = true
    failed.value = false
    try {
      const data = await adminUsers({
        keyword: keyword.value.trim() || undefined,
        // 'muted' 不是状态值，走独立参数；禁言中的人 status 仍是 0
        status: typeof statusFilter.value === 'number' ? statusFilter.value : undefined,
        muted: statusFilter.value === 'muted' ? true : undefined,
        cursor: cursor.value,
        size: PAGE,
      })
      const rows = data.list || []
      list.value = reset ? rows : [...list.value, ...rows]
      // total 是「当前筛选的全量数」(后端同一套 WHERE 计数),不是本页条数
      total.value = data.total ?? list.value.length
      hasMore.value = data.hasMore === true
      cursor.value = hasMore.value ? (data.nextCursor ?? null) : null
    } catch {
      // 失败不清 list:已看到的行比空白页有用,StateBlock 给重试
      failed.value = true
    } finally {
      loading.value = false
    }
  }

  /** 无限滚动下一页(哨兵在 AdminUsers.vue) */
  function loadMore() {
    if (!hasMore.value || loading.value) return
    load(false)
  }

  /** 搜索(回车/清空触发)。词变了必须回第一页 */
  function search() {
    load(true)
  }

  function setStatus(v) {
    if (statusFilter.value === v) return
    statusFilter.value = v
    load(true)
  }

  function openBan(user) {
    banning.value = user
    muting.value = null
    banDays.value = 7
    banReason.value = ''
    actionError.value = ''
  }

  function cancelBan() {
    banning.value = null
    actionError.value = ''
  }

  function openMute(user) {
    muting.value = user
    banning.value = null
    muteDays.value = 7
    muteReason.value = ''
    actionError.value = ''
  }

  function cancelMute() {
    muting.value = null
    actionError.value = ''
  }

  /**
   * 提交封禁。成功后整页重拉而不是行内补丁:
   * bannedUntil 由服务端(Redis 解封计划)算,本地猜一个时间戳
   * 会显示错误时间——「看着像对了」比「显示未知」更糟。
   */
  async function submitBan() {
    const user = banning.value
    if (!user || busy.value) return
    const reason = banReason.value.trim()
    if (!reason) {
      actionError.value = '封禁要写理由——audit_log 里「为什么封」只有这句话'
      return
    }
    busy.value = true
    actionError.value = ''
    try {
      await banUser(user.userId, banDays.value, reason)
      banning.value = null
      ElMessage.success(banDays.value === 0 ? '已永久封禁' : `已封禁 ${banDays.value} 天`)
      await load(true)
    } catch {
      // request.js 已弹错;面板留着,理由也留着,管理员改一下就能重提
    } finally {
      busy.value = false
    }
  }

  /**
   * 提交禁言。与封禁同一套约束：理由必填、成功后整页重拉。
   * 重复禁言只延长（后端保证），不会把已选天数改短。
   */
  async function submitMute() {
    const user = muting.value
    if (!user || busy.value) return
    const reason = muteReason.value.trim()
    if (!reason) {
      actionError.value = '禁言要写理由——理由会随通知发给对方'
      return
    }
    busy.value = true
    actionError.value = ''
    try {
      await muteUser(user.userId, muteDays.value, reason)
      muting.value = null
      ElMessage.success(`已禁言 ${muteDays.value} 天`)
      await load(true)
    } catch {
      // request.js 已弹错;面板与理由保留,管理员改一下就能重提
    } finally {
      busy.value = false
    }
  }

  /** 解封由 ConfirmDialog 确认后在视图里调这里 */
  async function submitUnban(user) {
    if (busy.value) return
    busy.value = true
    try {
      await unbanUser(user.userId)
      ElMessage.success('已解封')
      await load(true)
    } catch {
      // 422「不在封禁中」时 request.js 已弹错,重拉一次让列表回到真实状态
      await load(true)
    } finally {
      busy.value = false
    }
  }

  /** 解除禁言由 ConfirmDialog 确认后在视图里调这里 */
  async function submitUnmute(user) {
    if (busy.value) return
    busy.value = true
    try {
      await unmuteUser(user.userId)
      ElMessage.success('已解除禁言')
      await load(true)
    } catch {
      // 422「不在禁言中」/「封禁中」时重拉，让列表回到真实状态
      await load(true)
    } finally {
      busy.value = false
    }
  }

  return {
    list, total, hasMore, loading, failed,
    keyword, statusFilter,
    banning, banDays, banReason,
    muting, muteDays, muteReason,
    busy, actionError,
    load, loadMore, search, setStatus,
    openBan, cancelBan, submitBan,
    openMute, cancelMute, submitMute,
    submitUnban, submitUnmute,
  }
}
