/**
 * 学校归属写入的错误码 → 用户可执行的指引。
 *
 * [为什么需要这个文件]
 * 后端 dict 端点定稿后新增了两个 422 错误码:
 *   1040 院系无效(不存在 / 已停用 / 不属于该 schoolId)
 *   1041 年级无效
 *
 * request.js 的拦截器对任何非 200 一律 `ElMessage.error(message)` 然后 reject。
 * 所以这两个码**不会崩页面**,但用户只会看到一个转瞬即逝的 toast,
 * 然后表单静默不动 —— 不知道该去动哪个字段。这是典型的沉默失败:
 * 前端不报错、用户不知道要做什么。
 *
 * 这里把错误码映射成「哪个字段需要重选」,由调用方写到字段级错误上,
 * 而不是靠 toast 承载。
 */

/** 后端 ResultCode 里的字典相关错误码 */
export const DICT_ERR = {
  DEPT_INVALID: 1040,
  GRADE_INVALID: 1041,
  MAJOR_INVALID: 1057,
}

/**
 * 错误码 → 需要用户改哪个字段。
 * 返回 null 表示不是字典类错误,调用方走通用失败处理。
 */
const FIELD_OF = {
  [DICT_ERR.DEPT_INVALID]: 'deptId',
  [DICT_ERR.GRADE_INVALID]: 'gradeId',
  [DICT_ERR.MAJOR_INVALID]: 'majorId',
}

/** 字段级提示。与后端 message 语义一致,但措辞是指导动作而不是陈述失败。 */
const HINT_OF = {
  [DICT_ERR.DEPT_INVALID]: '这个学院已停用或不存在,重新选一个',
  [DICT_ERR.GRADE_INVALID]: '这个年级已停用或不存在,重新选一个',
  [DICT_ERR.MAJOR_INVALID]: '这个专业已停用或不存在,重新选一个',
}

/**
 * 从任意写法的错误里取出业务码。
 *
 * 走 HTTP 422 时 request.js 的 error 分支 reject 的是**原始 axios error**,
 * 码在 `error.response.data.code`;若后端改成 HTTP 200 + code!=200,
 * 则走业务错误分支 reject `new Error(message)` —— 那种写法下码会丢失,
 * 此时 `extractCode` 返回 null,调用方走通用失败处理(不会误判成字典失效)。
 *
 * @returns {number|null}
 */
export function extractCode(e) {
  const candidates = [
    e?.response?.data?.code,
    e?.code,
  ]
  for (const c of candidates) {
    if (typeof c === 'number' && Number.isFinite(c) && c > 0) return c
  }
  return null
}

/**
 * 判定这次写入是不是「字典失效」类错误。
 * @returns {null | { code:number, field:'deptId'|'gradeId'|'majorId', hint:string }}
 */
export function dictWriteError(e) {
  const code = extractCode(e)
  if (code === null) return null
  const field = FIELD_OF[code]
  if (!field) return null
  return { code, field, hint: HINT_OF[code] }
}

/**
 * 字典失效时的**附带动作**:拉一次新字典。
 *
 * 为什么必须重拉:失败原因往往是「该学院已停用」,而用户手里那份列表
 * 是几分钟前拉的,里面**仍然有**那个已停用的学院。只提示不刷新,
 * 用户会反复选中同一个无效项,进死循环。
 *
 * @param {import('pinia').Store} dictStore useDictStore()
 * @returns {Promise<void>} 刷新失败静默 —— 已有字段级提示兜底
 */
export async function refreshDictAfterInvalid(dictStore) {
  try {
    await dictStore.ensureLoaded(true)
  } catch {
    /* 刷不出来也不该覆盖字段级提示:那句话才是用户真正需要的信息 */
  }
}

/**
 * 把失效判定落到归属对象上:清掉那个已失效的 id,并返回给 SchoolFields 的提示。
 *
 * 为什么要**清值**而不是只给提示:失效的 id 大概率已不在新字典里,
 * el-select 找不到对应 option 就会把 id 本身当 label 显示出来 ——
 * 用户看到一个光秃秃的数字(如「12」),比不给提示更难理解。
 * 清掉之后下拉回到 placeholder,配合字段级错误就是完整的一条出路。
 *
 * @param {{deptId?:number|null, gradeId?:number|null, majorId?:number|null}} identity 归属对象(会被就地修改)
 * @param {null|{field:'deptId'|'gradeId'|'majorId', hint:string}} bad dictWriteError 的返回值
 * @returns {{dept?:string, grade?:string, major?:string}} 传给 SchoolFields 的 invalid
 */
export function applyDictInvalid(identity, bad) {
  if (!bad || !identity) return {}
  if (bad.field === 'deptId') identity.deptId = null
  if (bad.field === 'gradeId') identity.gradeId = null
  if (bad.field === 'majorId') identity.majorId = null
  if (bad.field === 'deptId') return { dept: bad.hint }
  if (bad.field === 'gradeId') return { grade: bad.hint }
  return { major: bad.hint }
}
