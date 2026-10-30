import { ref, computed, watch, onScopeDispose } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { list as listApi, join as joinApi, createCircle as createApi, search as searchApi } from '@/api/circle'
import { useUserStore } from '@/stores/user'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

/**
 * 圈子列表的数据与副作用(Circles.vue.md)
 * ------------------------------------------------------------------
 * 抽出来的原因:页面模板已有 110 行,再加搜索与创建逻辑就把门禁顶破。
 * 逻辑本身有**两条独立的失败语义**(列表拉取失败 vs 创建失败),
 * 混在一个 try 里会写成「一失败整页空」。
 */
export function useCircles() {
  const userStore = useUserStore()
  const { ensureLogin } = useGuestPrompt()

  const circles = ref([])
  const cursor = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
  /**
   * 可见圈子总数(后端 list 返回的 total)。
   * 用它而不是 circles.length —— 后者是「已加载条数」,无限滚动每页 20 条,
   * 没拉完时数字是错的(第一页拉满 20 显示「20 个圈」,实际有 80)。
   * 后端没给(null)时前端宁可不显示数字,也不显示错的。
   */
  const total = ref(null)
  /** 首屏列表拉取失败。翻页失败不置它 —— 已经看到内容了,不该整页变错误态 */
  const loadFailed = ref(false)
  /** 已经拉过一页。区分「没数据」与「还没拉」,否则首屏会先闪一下空态 */
  const loaded = ref(false)

  /** 搜索:空串=不搜索(展示全量列表) */
  const keyword = ref('')
  const searching = ref(false)
  const searchFailed = ref(false)
  /** 搜索结果。搜索态下用它替换 circles 的展示 */
  const results = ref([])

  const showCreate = ref(false)
  const newName = ref('')
  const creating = ref(false)
  /** 创建失败提示,贴输入框下方(设计 §3)。不用 toast 是因为抽屉会挡住它 */
  const createError = ref('')

  /** 是否处于搜索态 */
  const isSearching = computed(() => keyword.value.trim().length > 0)

  /**
   * 「我的」= 我参与的圈子(圈主/管理/成员都算)。
   *
   * 2026-10-21 改口径。改版前是两个互斥分组:
   *   joinedCircles = joined && myRole === 3  // 我加入的
   *   createdCircles = myRole === 1           // 我创建的
   * 于是 myRole === 2(我当管理)的圈两边都不进 —— 一个我天天要管的圈,
   * 页面上只能在「全部圈子」里找到自己,而那里看不出跟我的关系。
   *
   * 现在按「我与这个圈有没有关系」分,不再按「关系是哪一种」分;
   * 具体身份(圈主/管理/成员)用卡片上的小标签表达,不另开分组。
   * 另外 createdCircles 原来用 myRole===1 近似「我参与创建」,
   * 转让圈主后原创建者就从那个组里消失 —— 不再需要这个近似,缺口自动消失。
   */
  const myCircles = computed(() => circles.value.filter((c) => c.joined))

  /**
   * 「发现」= 剩下的圈子,与「我的」天然互斥且覆盖全集。
   *
   * 这里就是改版前 visibleCircles 想做的事 —— 那个 computed 的注释写着
   * 「全部圈子里要排除已在上方出现过的,否则同一个圈子一屏出现两次」,
   * 但 Circles.vue 的「全部圈子」分组渲染的是 circles 而不是它,
   * 去重逻辑写了没接上,于是桌面端同一批卡片画了两遍。
   * 现在分组只有两段,这段逻辑变成一个 filter,不存在「忘了接上」的余地。
   *
   * C10:发现段按最近活跃排(有帖的按最新一帖倒序,没帖的沉底)。
   * 排序在前端做而不是后端 —— 列表接口是 id 游标分页,改 ORDER BY 会毁掉
   * 游标语义;圈子是学校级规模(几十个),已加载集合里排一排够用。
   * 代价:没拉完时跨页顺序可能不完美,尾部文案「排完就是全部」对此负责。
   */
  const discoverCircles = computed(() =>
    circles.value
      .filter((c) => !c.joined)
      .slice()
      .sort((a, b) => {
        const ta = a.lastPostAt || ''
        const tb = b.lastPostAt || ''
        if (ta !== tb) return ta < tb ? 1 : -1 // 空串最小 -> 没帖的沉底
        return b.id - a.id
      }),
  )

  /**
   * 发现段分批展示(C15「换一批」)。一次给一小批,而不是把几十个圈糊一屏;
   * 换一批 = 轮换窗口,按最近活跃的顺序看下一组,轮完回头再看就是全部。
   * 总数 <= 一批时不显示「换一批」—— 没得换。
   */
  const DISCOVER_BATCH = 6
  const discoverOffset = ref(0)

  const discoverView = computed(() => {
    const all = discoverCircles.value
    if (all.length <= DISCOVER_BATCH) return all
    const start = discoverOffset.value % all.length
    return [...all.slice(start), ...all.slice(0, start)].slice(0, DISCOVER_BATCH)
  })

  const canSwapDiscover = computed(() => discoverCircles.value.length > DISCOVER_BATCH)

  function swapDiscover() {
    const n = discoverCircles.value.length
    if (n <= DISCOVER_BATCH) return
    discoverOffset.value = (discoverOffset.value + DISCOVER_BATCH) % n
  }

  /**
   * 顶栏「创建圈子」的守卫。
   * 只拦游客；账号年龄/信誉分由后端 CampusAccessService 判定，
   * 前端不预判，避免与后端阈值漂移后把能建的人挡在门外。
   */
  function openCreate() {
    if (!userStore.hasToken) return 'need-login'
    createError.value = ''
    showCreate.value = true
    return 'opened'
  }

  async function load() {
    loading.value = true
    loadFailed.value = false
    try {
      // 不传关键字:GET /circle 没有 keyword 参数(已查证)。
      const data = await listApi(cursor.value, 20)
      circles.value = cursor.value ? [...circles.value, ...data.list] : data.list
      cursor.value = data.hasMore ? data.nextCursor : null
      hasMore.value = data.hasMore
      total.value = data.total ?? total.value
      loaded.value = true
    } catch {
      // 只有首屏失败才整页报错:已经有内容时追加失败不该把列表清空
      loadFailed.value = circles.value.length === 0
    } finally {
      loading.value = false
    }
  }

  /**
   * 搜圈子。走 /search?type=circle(后端 SearchController 支持 name LIKE)。
   *
   * 2026-10-21 起结果带 joined/myRole(后端 toCircleVos 与 /circle 列表同口径,
   * 见 F3)。所以搜索结果里加过的圈渲染「已加入」,不再是无效「加入」按钮。
   */
  async function runSearch() {
    const q = keyword.value.trim()
    if (!q) {
      results.value = []
      searchFailed.value = false
      return
    }
    searching.value = true
    searchFailed.value = false
    try {
      const data = await searchApi(q, 'circle', 20)
      results.value = data?.circles || []
    } catch {
      results.value = []
      searchFailed.value = true
    } finally {
      searching.value = false
    }
  }

  /**
   * 输入即搜(C11):300ms 防抖。回车走 runSearch 立即搜。
   * 防抖收在 composable 而不是模板:模板里挂 @input + setTimeout 会把
   * 计时器泄漏在组件卸载后(路由走了还在搜)。onScopeDispose 随组件作用域回收。
   */
  let searchTimer = null
  watch(keyword, () => {
    clearTimeout(searchTimer)
    if (!keyword.value.trim()) {
      results.value = []
      searchFailed.value = false
      searching.value = false
      return
    }
    searchTimer = setTimeout(runSearch, 300)
  })
  onScopeDispose(() => clearTimeout(searchTimer))

  /** 清空搜索词,回到全量列表。不重拉列表 —— 数据已经在内存里 */
  function clearSearch() {
    keyword.value = ''
    results.value = []
    searchFailed.value = false
  }

  async function joinCircle(c) {
    // 游客只读:加入圈子要账号,先弹登录提示
    if (!userStore.hasToken) {
      await ensureLogin('登录后才能加入圈子，现在去登录吗？')
      return
    }
    try {
      await joinApi(c.id)
      c.joined = true
      ElMessage.success(`已加入「${c.name}」`)
    } catch {
      // 按钮回到「加入」态(c.joined 未改)+ request.js 已弹错(设计 §3)
      ElMessage.closeAll()
    }
  }

  /**
   * 退圈/解散不在列表页做(2026-10-21 随设计稿挪进 CircleDetail.vue):
   * 卡片上只留「加入 / 已加入」两态(C8),破坏性动作收进详情页,
   * 那里有自己的 useCircleDetail/useCircleAdminActions 与二次确认。
   * 原先挂在这里的 leaveCircle/disbandCircle 一并删除 —— 没有调用方的
   * 破坏性函数留在 composable 里只会等着被误接线。
   */

  async function create() {
    const name = newName.value.trim()
    if (!name || creating.value) return
    // 前端先拦「不足 3 字」:用户打第 3 个字就该看到提示,不依赖后端 422
    if ([...name].length < 3) {
      createError.value = '名字至少 3 个字'
      return
    }
    // 必须含汉字(可搭配字母/数字,如「23届网安」);纯数字/纯字母/纯符号拒
    if (!/[一-龥]/.test(name)) {
      createError.value = '名字需含汉字,不能是纯数字/纯字母'
      return
    }
    creating.value = true
    createError.value = ''
    try {
      await createApi(name)
      showCreate.value = false
      newName.value = ''
      ElMessage.success('圈子建好了，去发第一条吧')
      // 回到列表顶部:新建的圈子在最前,不重置游标的话它会落在当前分页之外,
      // 用户会以为「建了但没出现」
      cursor.value = null
      await load()
    } catch (e) {
      // 输入的名字**保留** —— 清空等于让用户重打一遍(设计 §3)。
      // 后端业务文案直接透传,不用让用户去翻 Network 面板猜是哪条规则拦的。
      // 「换个名字再试」只对名字类错误(409 重名/撞预留名、422 长度/敏感词)成立;
      // 403 是账号账龄/信誉分门槛,换名字再试是误导。
      const status = e?.response?.status
      const msg = e?.response?.data?.message
      const renameable = status === 409 || status === 422
      createError.value = msg
        ? (renameable ? `${msg}(换个名字再试)` : msg)
        : '没创建成功，再点一次'
    } finally {
      creating.value = false
    }
  }

  return {
    circles, cursor, hasMore, loading, loadFailed, loaded, total,
    keyword, searching, searchFailed, results, isSearching,
    myCircles, discoverCircles, discoverView, canSwapDiscover, swapDiscover,
    showCreate, newName, creating, createError,
    openCreate, load, runSearch, clearSearch, joinCircle, create,
  }
}