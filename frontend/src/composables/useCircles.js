import { ref, computed } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { list as listApi, join as joinApi, createCircle as createApi, search as searchApi } from '@/api/circle'
import { useUserStore } from '@/stores/user'
import { isIdentityVerified } from '@/utils/mask'

/**
 * 圈子列表的数据与副作用(Circles.vue.md)
 * ------------------------------------------------------------------
 * 抽出来的原因:页面模板已有 110 行,再加 AC-11 判定与搜索就把门禁顶破。
 * 逻辑本身有**两条独立的失败语义**(列表拉取失败 vs 创建失败),
 * 混在一个 try 里会写成「一失败整页空」。
 */
export function useCircles() {
  const userStore = useUserStore()

  const circles = ref([])
  const cursor = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
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

  /** 当前要渲染的列表。搜索态下只有搜索结果,不做「我加入的/全部」分组 */
  const rows = computed(() => (isSearching.value ? results.value : circles.value))

  /** 「我加入的」分组。空则整组隐藏(模板 v-if),不留空分组头 */
  const joinedCircles = computed(() => circles.value.filter((c) => c.joined))

  /** 「全部圈子」里要排除已在上方出现过的,否则同一个圈子一屏出现两次 */
  const visibleCircles = computed(() =>
    joinedCircles.value.length
      ? circles.value.filter((c) => !c.joined)
      : circles.value,
  )

  /** 顶栏「创建圈子」的守卫。AC-11:未认证给说明而不是沉默失败 */
  function openCreate() {
    if (!isIdentityVerified(userStore.userInfo)) return 'need-verify'
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
   * **搜索结果没有 joined 字段** —— SearchController.toCircleVo 只回
   * id/name/memberCount,不查关系表。所以结果里的圈子一律渲染成「加入」,
   * 即使用户已经加过。这是契约缺口(已在交付报告里列给后端),
   * 前端不做「猜它加没加」的推断:猜错就是给已加入的圈子一个无效按钮。
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

  /** 清空搜索词,回到全量列表。不重拉列表 —— 数据已经在内存里 */
  function clearSearch() {
    keyword.value = ''
    results.value = []
    searchFailed.value = false
  }

  async function joinCircle(c) {
    try {
      await joinApi(c.id)
      c.joined = true
      ElMessage.success(`已加入「${c.name}」`)
    } catch {
      // 按钮回到「加入」态(c.joined 未改)+ request.js 已弹错(设计 §3)
      ElMessage.closeAll()
    }
  }

  async function create() {
    const name = newName.value.trim()
    if (!name || creating.value) return
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
    } catch {
      // 输入的名字**保留** —— 清空等于让用户重打一遍(设计 §3)
      createError.value = '没创建成功，再点一次'
    } finally {
      creating.value = false
    }
  }

  return {
    circles, cursor, hasMore, loading, loadFailed, loaded,
    keyword, searching, searchFailed, results, isSearching, rows,
    joinedCircles, visibleCircles,
    showCreate, newName, creating, createError,
    openCreate, load, runSearch, clearSearch, joinCircle, create,
  }
}