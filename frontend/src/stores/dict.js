import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as dictApi from '@/api/dict'
import {
  DICT_FIELDS, FALLBACK_DEPTS, FALLBACK_GRADES, formatGradeName,
} from '@/constants/schoolDict'

/**
 * 学校字典 store(学院 / 年级)
 * ------------------------------------------------------------------
 * 为什么缓存:
 *  1. 字典是**只读且几乎不变**的数据(单校 10~30 院系、5 个年级)。
 *     引导第 1 步、个人主页、设置页都会用到,每个组件实例各拉一次
 *     是纯粹的浪费。
 *  2. `inflight` 单飞:并发调用共享同一个 Promise,不会打出 N 个请求。
 *
 * 为什么不写进 user store:字典不是「我的」数据,是全局只读参照系。
 *  放一起会让 user store 变成什么都往里塞的杂物袋。
 *
 * 失败降级:用本地种子当**只读下拉选项**,不是回退到自由文本。
 *  用户仍然只能选固定选项,写回去的仍然是 id —— 同院系推荐(AC-14)不会漏人。
 *  `degraded` 标记出来是为了让 UI 能提示「列表来自本地缓存」,不静默假装成功。
 */
export const useDictStore = defineStore('dict', () => {
  const depts = ref([])
  const grades = ref([])
  const loading = ref(false)
  /** true 表示当前数据来自本地种子(后端字典不可用) */
  const degraded = ref(false)
  /** 上次拉取失败的原因,用于展示具体错误而不是「加载失败」 */
  const error = ref('')
  /** 字典所属学校。MVP 单校恒为 1,存下来是为了不把 schoolId 写死在组件里 */
  const schoolId = ref(null)

  /**
   * 全校专业(挂载时一次拉取,后端带 Redis 缓存)。
   * 每个元素含 { id, deptId, name, code, level, sort }。
   * 专业下拉改为**纯前端按「当前学院 + 本专科」过滤**这个数组,
   * 不再「每选一次组合发一次请求」——彻底消除竞态、loading 卡死、
   * 以及「首次选完学院+本专科却没加载出来」的问题(用户只动其中几个选项也必然出专业)。
   */
  const allMajors = ref([])
  /** 全校专业是否已拉取成功(决定专业下拉能否按当前选择即时过滤) */
  const majorsLoaded = ref(false)
  /** 全校专业首次拉取中(挂载时)——专业下拉据此显示加载动画 */
  const majorsLoading = ref(false)
  /** 全校专业拉取失败原因(失败时才需要重试入口) */
  const majorsError = ref('')

  let loaded = false
  let inflight = null
  /** 全校专业请求的单飞句柄(父页面回填与 SchoolFields 挂载会同时调用) */
  let majorsInflight = null

  const deptOptions = computed(() => depts.value)
  const gradeOptions = computed(() =>
    grades.value.map((g) => ({ ...g, name: formatGradeName(g.name, g.enrollYear) })),
  )

  /**
   * 按「当前选中的学院 + 本专科」从已拉取的全校专业里过滤出下拉选项。
   * 纯前端同步计算,随当前选择即时变化,无网络、无竞态、不依赖请求到达顺序。
   *
   * @param {number} deptId 学院 id
   * @param {number} level  本专科 1=本科 2=专科
   */
  function majorOptionsFor(deptId, level) {
    if (!deptId || !level) return []
    const d = Number(deptId)
    const l = Number(level)
    return allMajors.value.filter(
      (m) => Number(m.deptId) === d && Number(m.level) === l,
    )
  }

  /**
   * 归一化字典项。
   *
   * 容错三种形状(后端实现与前端并行推进,形状对不上不该整页白屏):
   *   { depts: [...], grades: [...] }  —— openapi §SchoolDictResponse
   *   { list: [...] }                 —— 单数组
   *   [{ id, name }, ...]             —— 裸数组
   *
   * 年级额外带出 enrollYear:openapi 明确说不要从 name 截年份。
   */
  function normalize(raw, key, withYear = false) {
    let arr = raw?.[key]
    if (!Array.isArray(arr)) arr = raw?.list
    if (!Array.isArray(arr) && Array.isArray(raw)) arr = raw
    if (!Array.isArray(arr)) return []
    return arr
      .map((it) => {
        const item = {
          id: Number(it?.[DICT_FIELDS.itemId]),
          name: String(it?.[DICT_FIELDS.itemName] ?? ''),
        }
        if (withYear) {
          item.enrollYear = Number(it?.[DICT_FIELDS.gradeEnrollYear]) || null
        }
        return item
      })
      // 过滤掉 id 解析不出来的项:下游按 id 写入,脏 id 会写出指向不存在行的数据
      .filter((it) => Number.isFinite(it.id) && it.name)
  }

  /**
   * 拉取字典。已加载则直接返回缓存,**不重复请求**。
   * @param {boolean} force 强制重新拉(下拉点「重试」时用)
   */
  async function ensureLoaded(force = false) {
    if (loaded && !force) return
    // 单飞:并发调用共享同一个 in-flight Promise
    if (inflight && !force) return inflight

    loading.value = true
    error.value = ''
    inflight = dictApi
      .schoolDict()
      .then((raw) => {
        const d = normalize(raw, DICT_FIELDS.deptsKey)
        const g = normalize(raw, DICT_FIELDS.gradesKey, true)
        // 两个数组都空才判定为「后端没给」——部分缺失时用种子补齐缺的那个,
        // 而不是整体丢弃后端数据(宁可少一个学院,不要一个都没有)
        depts.value = d.length ? d : FALLBACK_DEPTS
        grades.value = g.length ? g : FALLBACK_GRADES
        degraded.value = !d.length || !g.length
        const sid = Number(raw?.[DICT_FIELDS.schoolId])
        schoolId.value = Number.isFinite(sid) && sid > 0 ? sid : null
        loaded = true
      })
      .catch((e) => {
        depts.value = FALLBACK_DEPTS
        grades.value = FALLBACK_GRADES
        degraded.value = true
        error.value = e?.message || '字典没拉出来'
        // 不置 loaded:true —— 允许用户点「重试」重新拉。
        // 反过来(失败也标记已加载)会让重试按钮永远打不出请求。
      })
      .finally(() => {
        loading.value = false
        inflight = null
      })

    return inflight
  }

  /** 按 id 反查名称。展示兜底用,查不到返回空串而不是 undefined */
  function deptName(id) {
    if (id === null || id === undefined) return ''
    return depts.value.find((d) => Number(d.id) === Number(id))?.name || ''
  }

  function gradeName(id) {
    if (id === null || id === undefined) return ''
    const hit = grades.value.find((g) => Number(g.id) === Number(id))
    return hit ? formatGradeName(hit.name, hit.enrollYear) : ''
  }

  /** 学院下拉的可搜索判定:最长名称超过 12 个字就必须可搜索 */
  const deptSearchable = computed(() =>
    depts.value.some((d) => d.name.length > 12),
  )

  /**
   * 一次拉取全校专业(GET /dict/majors/all,后端带 Redis 缓存 TTL 10min)。
   * 挂载时调用一次,之后专业下拉纯前端按「学院 + 本专科」过滤,不再逐组合发请求。
   *
   * **单飞**:设置页父组件 load() 回填专业、SchoolFields 子组件挂载都要调这里,
   * 两者几乎同时发生。没有单飞就会打出两个内容完全相同的请求,
   * 后到的响应还会覆盖先到的 allMajors(若两次数据不同则产生闪烁)。
   *
   * @param {number} [schoolId] 学校 id,缺省由后端取默认(恒 1)
   * @param {boolean} force 强制重新拉(重试时用)
   */
  async function ensureMajorsLoaded(schoolId, force = false) {
    if (majorsLoaded.value && !force) return
    if (majorsInflight && !force) return majorsInflight
    majorsLoading.value = true
    majorsError.value = ''
    const p = (async () => {
      try {
        const raw = await dictApi.allMajors(schoolId)
        const list = Array.isArray(raw) ? raw : raw?.majors || []
        allMajors.value = list
          .map((it) => ({
            id: Number(it?.id),
            deptId: Number(it?.deptId),
            name: String(it?.name ?? ''),
            code: it?.code != null ? String(it?.code) : '',
            level: Number(it?.level),
            sort: Number(it?.sort ?? 0),
          }))
          .filter((it) => Number.isFinite(it.id) && it.name)
        majorsLoaded.value = true
      } catch (e) {
        // 失败仅清空,组件给出重试入口;不阻断整体(院系/年级仍可用)。
        allMajors.value = []
        majorsError.value = e?.message || '专业列表没拉出来'
      } finally {
        majorsLoading.value = false
        majorsInflight = null
      }
    })()
    majorsInflight = p
    return p
  }

  /**
   * 按专业 id 反查本专科层次(1=本科 2=专科)。
   *
   * 为什么需要:user 表只存 major_id,不存 level(层次是专业的属性,
   * 存两处会出现「专业是专科、层次填本科」的不一致)。设置页回填时
   * 要拿它把「本专科」下拉恢复成已选项,否则专业下拉会被 level 为空禁用。
   *
   * 专业未被 allMajors 收录(如已停用)时返回 null,由调用方降级处理。
   */
  function levelOfMajor(majorId) {
    if (majorId === null || majorId === undefined) return null
    const hit = allMajors.value.find((m) => Number(m.id) === Number(majorId))
    return hit ? hit.level : null
  }

  /**
   * 兼容入口:原「按学院+本专科发请求」的调用点改为「确保全校专业已加载」。
   * 数据已在挂载时一次拉齐,这里多数情况直接命中已加载、无网络。
   *
   * @param {number} deptId  学院 id(兼容参数,过滤已在前端做)
   * @param {number} level   本专科(兼容参数)
   * @param {boolean} force  强制重新拉全校专业
   */
  async function loadMajors(deptId, level, force = false) {
    if (!majorsLoaded.value || force) {
      await ensureMajorsLoaded(undefined, force)
    }
  }

  return {
    depts, grades, loading, degraded, error, schoolId,
    allMajors, majorsLoaded, majorsLoading, majorsError,
    deptOptions, gradeOptions, deptSearchable, majorOptionsFor, levelOfMajor,
    ensureLoaded, ensureMajorsLoaded, deptName, gradeName, loadMajors,
  }
})

export default useDictStore