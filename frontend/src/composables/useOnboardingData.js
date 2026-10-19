import { ref } from 'vue'
import * as api from '@/api/onboarding'
import { FALLBACK_MIN_SELECT, FALLBACK_MAX_SELECT } from '@/constants/schoolDict'

/**
 * 标签分类的中文名。与后端 TagServiceImpl.CATEGORY_NAMES 同源(1-5 固定值):
 * /onboarding/tags 返回**平铺**数组({id,name,category}),StepTags 期望
 * 分组结构({category,categoryName,tags[]}),这里做一次转换。
 * 早前直接把平铺数组塞给 groups,g.tags 是 undefined → 标签区整片空白。
 */
const TAG_CATEGORY_NAMES = { 1: '兴趣', 2: '游戏', 3: '运动', 4: '学习', 5: '生活' }

function groupTagsByCategory(flat) {
  const byCat = new Map()
  for (const t of flat) {
    if (!t || t.id == null) continue
    if (!byCat.has(t.category)) {
      byCat.set(t.category, {
        category: t.category,
        categoryName: TAG_CATEGORY_NAMES[t.category] || '其他',
        tags: [],
      })
    }
    byCat.get(t.category).tags.push(t)
  }
  return [...byCat.values()].sort((a, b) => a.category - b.category)
}

/**
 * 引导页第 2/3 步的数据加载(标签 + 推荐人/圈子)。
 *
 * 从 Onboarding.vue 抽出:这两块各有独立失败语义(第 2 步失败要整步跳过,
 * 第 3 步失败要各自降级),混在主流程里很容易把「整步跳过」写成「填空列表」。
 */
export function useOnboardingData(onTagEmpty) {
  /* ---------------- 第 2 步:兴趣标签 ---------------- */
  const tagIds = ref([])
  const tagGroups = ref([])
  const tagLoading = ref(false)
  const tagFailed = ref(false)
  const minSelect = ref(FALLBACK_MIN_SELECT)
  const maxSelect = ref(FALLBACK_MAX_SELECT)

  /**
   * 标签加载失败时**整步跳过**自动进第 3 步(Onboarding.vue.md §3 空态 2)。
   * 不给用户一个空标签云让他面对 —— 那是死胡同。
   */
  async function loadTags() {
    tagLoading.value = true
    tagFailed.value = false
    try {
      const data = await api.tags(60)
      tagGroups.value = groupTagsByCategory(data?.tags || data?.list || [])
      // min/max 由后端下发,只兜底不覆盖
      minSelect.value = data?.minSelect || FALLBACK_MIN_SELECT
      maxSelect.value = data?.maxSelect || FALLBACK_MAX_SELECT
      if (tagGroups.value.length === 0) onTagEmpty?.()
    } catch {
      tagFailed.value = true
    } finally {
      tagLoading.value = false
    }
  }

  /**
   * 选到上限外时**取消最旧的一个**并继续(§1.2),
   * 而不是拒绝选中 —— 拒绝会让用户以为控件坏了。
   */
  function toggleTag(id) {
    const i = tagIds.value.indexOf(id)
    if (i >= 0) {
      tagIds.value.splice(i, 1)
      return
    }
    tagIds.value.push(id)
    if (tagIds.value.length > maxSelect.value) tagIds.value.shift()
  }

  /* ---------------- 第 3 步:推荐人 + 圈子 ---------------- */
  const users = ref([])
  const circles = ref([])
  const userIds = ref([])
  const circleIds = ref([])
  const recLoading = ref(false)
  const usersFailed = ref(false)
  const circlesFailed = ref(false)

  /**
   * 两个推荐请求**并发且各自降级**(§3 错误态):
   * 任一失败都只把该分组降级为一行说明,不阻塞引导完成。
   *
   * 用 allSettled 而非 all —— all 会在第一个失败时立刻 reject,
   * 另一个请求的结果被丢弃,已成功的分组会被误判成空。
   */
  async function loadRecommend() {
    recLoading.value = true
    const [u, c] = await Promise.allSettled([
      api.recommendUsers(12),
      api.recommendCircles(8),
    ])
    usersFailed.value = u.status === 'rejected'
    circlesFailed.value = c.status === 'rejected'

    if (u.status === 'fulfilled') {
      const all = u.value?.users || []
      // P3:只展示与当前用户有共同兴趣的同学(共同标签数>0),按共同兴趣数从多到少排序。
      // 共同标签数由后端 selectRecommendUsers 的相关子查询算出,前端只是消费排序结果。
      // 各只取 5 条:引导是「给个起点」不是「让你挑完」(§1.3)
      users.value = all
        .filter((x) => (x.commonTagCount || 0) > 0)
        .sort((a, b) => (b.commonTagCount || 0) - (a.commonTagCount || 0))
        .slice(0, 5)
      // 后端下发 followed:已关注的渲染为已关注态,且默认计入选中
      userIds.value = users.value.filter((x) => x.followed).map((x) => x.id)
    }
    if (c.status === 'fulfilled') {
      const all = c.value?.circles || []
      // P3:按共同兴趣数从多到少排序。圈子目前后端没有 circle_tag 数据(§6 场景 2 未建),
      // commonTagCount 恒为 0,排序暂时不改变顺序;待圈子标签表建成后即可自动生效。
      circles.value = [...all]
        .sort((a, b) => (b.commonTagCount || 0) - (a.commonTagCount || 0))
        .slice(0, 5)
      circleIds.value = circles.value.filter((x) => x.joined).map((x) => x.id)
    }
    recLoading.value = false
  }

  /** 跳过时清空选择(第 2 步清标签,第 3 步清关注) */
  function clearTags() {
    tagIds.value = []
  }
  function clearFollows() {
    userIds.value = []
    circleIds.value = []
  }

  return {
    tagIds, tagGroups, tagLoading, tagFailed, minSelect, maxSelect,
    loadTags, toggleTag, clearTags,
    users, circles, userIds, circleIds, recLoading, usersFailed, circlesFailed,
    loadRecommend, clearFollows,
  }
}

/** 单选集合的 toggle:关注人和圈子都是「点一下选中 / 再点取消」 */
export function toggleIn(list, id) {
  const i = list.indexOf(id)
  if (i >= 0) list.splice(i, 1)
  else list.push(id)
}