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

  let loaded = false
  let inflight = null

  const deptOptions = computed(() => depts.value)
  const gradeOptions = computed(() =>
    grades.value.map((g) => ({ ...g, name: formatGradeName(g.name, g.enrollYear) })),
  )

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

  return {
    depts, grades, loading, degraded, error, schoolId,
    deptOptions, gradeOptions, deptSearchable,
    ensureLoaded, deptName, gradeName,
  }
})

export default useDictStore