import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import dayjs from 'dayjs'
import { logs } from '@/api/audit'
import { mentionCandidates } from '@/api/auth'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useUserStore } from '@/stores/user'
import { AUDIT_ACTIONS } from '@/constants/auditActions'

/**
 * 操作日志页的数据与状态(AdminAudit.vue.md)
 *
 * 从 AdminAudit.vue 抽出,因为逻辑有 100 多行而模板只占一小半。
 * 它有**三条独立的失败语义**,混在页面里很容易被写成「一失败全页报错」:
 *   1. 查询失败(列表拉不出来)→ 给「重试」
 *   2. 用户搜索失败 → 挂在输入框下方,不影响主查询
 *   3. 翻页失败 → 保留已有列表,不清空
 *
 * 与设计的三处核心差异,以及为什么:
 *
 * 1. **砍掉全量用户下拉**(设计 §7)。原实现调 `mentionCandidates('')`
 *    一次拉全部用户填进 el-select。三个问题:手机上拉全表的流量与等待都不可接受;
 *    EP 全量下拉在移动端是页面级弹层,与筛选面板叠成两层巨型弹层;
 *    它是本页唯一的「非只读」行为。改为「输入昵称关键字 + 选中后换成标签」。
 *
 * 2. **筛选区折叠**(设计 §1)。移动端默认收起,顶栏 Filter 图标切换。
 *    桌面端常驻展开 —— 管理员在桌面上要精确选条件,收起它是倒退。
 *
 * 3. **桌面端恢复全量下拉**(设计 §2)。桌面上管理员就是要精确选人,
 *    而且桌面网络与弹层空间都撑得住。这里只在非移动端拉一次,
 *    拉取失败静默降级成搜索框,不留一个点不动的空下拉。
 */
export function useAuditLog() {
  const isMobile = useIsMobile()

  /**
   * 权限:路由已有 requiresAdmin 守卫,这里是第二道 —— 直接输 URL 进来
   * 或 token 过期后 role 变了,页面要给出「不是你该看的」而不是空列表。
   * 取不到 userInfo 时不拦(可能只是还没拉完),交给接口 403 兜底。
   */
  const user = useUserStore()
  const denied = ref(false)
  const known = computed(() => !!user.userInfo)
  if (known.value && !user.isAdmin) denied.value = true

  /** 动作枚举(与后端 audit 写入一致,单一数据源在 constants/auditActions.js) */
  const ACTIONS = AUDIT_ACTIONS

  const actionLabel = (v) => (ACTIONS.find((a) => a.v === v) || { l: v || '-' }).l
  const actionTag = (v) => (ACTIONS.find((a) => a.v === v) || { t: 'info' }).t

  /* ---------------- 筛选状态 ---------------- */
  /**
   * userId 是真正发给接口的值;user 存被选中的整个对象,只为了回显条显示昵称。
   * 分开存是因为桌面端 el-select 直接给的是 id,移动端搜索选中给的是对象 ——
   * 强行合并成一个字段,两端就得各写一次转换,迟早有一端漏。
   */
  const f = ref({ action: '', userId: null, user: null, keyword: '', nickname: '' })

  /** 时间范围:[开始, 结束],字符串 yyyy-MM-dd HH:mm:ss;空数组=不限 */
  const timeRange = ref([])

  /** 输入的是昵称关键字;选中后输入框收成一个标签 */
  const userInput = ref('')
  /** 搜索结果。只在有关键字时才拉 —— 空关键字就是全量,那是要避免的 */
  const userOptions = ref([])
  const searching = ref(false)
  /** 搜索失败的提示挂在输入框下方,不影响主查询(设计 §3) */
  const userSearchError = ref('')
  let searchTimer = null

  /** 筛选区展开态。桌面端常驻展开,不看这个值 */
  const filterOpen = ref(false)
  const expanded = computed(() => !isMobile.value || filterOpen.value)

  /**
   * 桌面端的全量用户下拉(设计 §2 要求恢复)。只在非移动端拉一次,
   * 拉不到就静默置空 —— 桌面上网络与弹层空间都撑得住,不让它拖累主查询。
   */
  const allUsers = ref([])
  const desktopUsersReady = ref(false)
  async function loadDesktopUsers() {
    if (desktopUsersReady.value || isMobile.value) return
    desktopUsersReady.value = true
    try {
      const all = await mentionCandidates('')
      allUsers.value = Array.isArray(all) ? all : []
    } catch {
      allUsers.value = []
    }
  }

  /* ---------------- 列表状态 ---------------- */
  const list = ref([])
  const cursor = ref(null)
  const hasMore = ref(false)
  const loading = ref(false)
  /** 查询失败。要区别于「查了没结果」—— 前者给重试,后者给清空筛选 */
  const loadFailed = ref(false)
  const observer = ref(null)

  function fmt(t) {
    return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : ''
  }

  /**
   * 已选条件的回显条(设计 §1 行 3)。只列真正生效的。
   * 用户标签里存的是整个对象而不是 id,是为了显示昵称 —— 只存 id 的话
   * 回显条只能显示一个数字,管理员看不懂自己筛的是谁。
   */
  const activeChips = computed(() => {
    const chips = []
    if (f.value.action) {
      chips.push({ key: 'action', label: `动作：${actionLabel(f.value.action)}` })
    }
    if (f.value.userId) {
      const name =
        f.value.user?.nickname ||
        allUsers.value.find((u) => u.id === f.value.userId)?.nickname ||
        '已选用户'
      chips.push({ key: 'user', label: `用户：${name}` })
    }
    const kw = f.value.keyword.trim()
    if (kw) chips.push({ key: 'keyword', label: `关键字：${kw}` })
    const nm = f.value.nickname.trim()
    if (nm) chips.push({ key: 'nickname', label: `操作者：${nm}` })
    if (timeRange.value?.[0] && timeRange.value?.[1]) {
      chips.push({ key: 'time', label: `时间：${timeRange.value[0]} ~ ${timeRange.value[1]}` })
    }
    return chips
  })

  const hasFilter = computed(() => activeChips.value.length > 0)

  function setupObserver() {
    observer.value?.disconnect()
    observer.value = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !loading.value && hasMore.value) load(false)
      },
      { rootMargin: '200px' }
    )
    const el = document.querySelector('.sentinel')
    if (el) observer.value.observe(el)
  }

  async function load(reset) {
    if (loading.value) return
    loading.value = true
    loadFailed.value = false
    try {
      if (reset) {
        cursor.value = null
        list.value = []
      }
      const data = await logs(cursor.value, 30, {
        action: f.value.action || undefined,
        userId: f.value.userId || undefined,
        keyword: f.value.keyword?.trim() || undefined,
        nickname: f.value.nickname?.trim() || undefined,
        start: timeRange.value?.[0] || undefined,
        end: timeRange.value?.[1] || undefined,
      })
      list.value = reset ? data.list : [...list.value, ...data.list]
      cursor.value = data.hasMore ? data.nextCursor : null
      hasMore.value = data.hasMore
      await nextTick()
      setupObserver()
    } catch (e) {
      /*
        保留已有列表不清空:翻页失败时把已经看到的 30 条清掉是最糟的反馈。
        同时把 hasMore 置false,否则哨兵会无限重试同一个失败的请求。
      */
      // 403 是「不该看」而不是「拉不到」,要换成权限不足区块而不是错误态
      if (e?.response?.status === 403) {
        denied.value = true
        return
      }
      loadFailed.value = true
      hasMore.value = false
    } finally {
      loading.value = false
    }
  }

  /** 「清空筛选」出口:三个条件都置空并重拉第一页 */
  async function resetFilters() {
    f.value = { action: '', userId: null, user: null, keyword: '', nickname: '' }
    timeRange.value = []
    userInput.value = ''
    userOptions.value = []
    userSearchError.value = ''
    await load(true)
  }

  /** 移除单个条件 chip。剩余 chips 直接重排,不加动画(加了会抖) */
  async function removeChip(key) {
    if (key === 'action') f.value.action = ''
    if (key === 'user') {
      f.value.userId = null
      f.value.user = null
      userInput.value = ''
      userOptions.value = []
    }
    if (key === 'keyword') f.value.keyword = ''
    if (key === 'nickname') f.value.nickname = ''
    if (key === 'time') timeRange.value = []
    await load(true)
  }

  /**
   * 按关键字搜用户。防抖 300ms —— 每敲一个字发一次请求,
   * 慢网络下后发的先到,列表会闪。
   */
  function onUserInput() {
    // 输入即取消已选:用户开始改关键字就说明想换人,
    // 此时旧的选中标签继续生效会让他以为搜的是那个人
    f.value.user = null
    f.value.userId = null
    userSearchError.value = ''
    if (searchTimer) clearTimeout(searchTimer)
    const kw = userInput.value.trim()
    // 空关键字 = 全量查询,正是设计 §7 要避免的。这里直接清空,不请求
    if (!kw) {
      userOptions.value = []
      return
    }
    searchTimer = setTimeout(async () => {
      searching.value = true
      try {
        const all = await mentionCandidates(kw)
        userOptions.value = Array.isArray(all) ? all : []
        if (userOptions.value.length === 0) {
          userSearchError.value = '没搜到这个人，换个关键字试试'
        }
      } catch {
        userSearchError.value = '搜索没成功，检查一下网络'
        userOptions.value = []
      } finally {
        searching.value = false
      }
    }, 300)
  }

  function pickUser(u) {
    f.value.user = u
    f.value.userId = u?.id ?? null
    userInput.value = ''
    userOptions.value = []
    userSearchError.value = ''
    load(true)
  }

  /** 桌面端下拉直接给 id,这里补上对象以便回显条显示昵称 */
  function pickUserById(id) {
    if (!id) {
      f.value.user = null
      f.value.userId = null
      return
    }
    f.value.userId = id
    f.value.user = allUsers.value.find((u) => u.id === id) || null
  }

  onMounted(() => {
    load(true)
    loadDesktopUsers()
  })

  onBeforeUnmount(() => {
    observer.value?.disconnect()
    if (searchTimer) clearTimeout(searchTimer)
  })

  /* ---------------- 详情抽屉 ---------------- */
  const selected = ref(null)
  const drawerOpen = ref(false)
  function openDetail(l) { selected.value = l; drawerOpen.value = true }

  return {
    // 权限与枚举
    denied, ACTIONS, actionLabel, actionTag, fmt,
    // 筛选
    f, timeRange, expanded, filterOpen, activeChips, hasFilter,
    userInput, userOptions, searching, userSearchError, allUsers,
    onUserInput, pickUser, pickUserById, removeChip, resetFilters,
    // 列表
    list, loading, hasMore, loadFailed, load,
    // 详情抽屉
    selected, drawerOpen, openDetail,
  }
}