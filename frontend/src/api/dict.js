import request from '@/utils/request'
import { DICT_ENDPOINT, DICT_FIELDS, SCHOOL_PROFILE_PAYLOAD } from '@/constants/schoolDict'
import { gradeStage } from '@/utils/gradeStage'

/**
 * 学校字典 API(学院 / 年级)
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml(字典端点由后端任务 #22 落地,以它为准)
 * 结构风格参照现有 GET /tags 兴趣标签字典。
 *
 * 为什么需要它:学院/年级原为自由文本,「计算机学院」与「计科」会被算成两个院系,
 * 同院系推荐(AC-14)直接漏人。改为字典 id 后,同院系是同一行的主键比较。
 */

/**
 * 一次拉全院系 + 年级。
 *
 * 合成一个端点而不是两个:引导第 1 步三个下拉要同时可用,
 * 分两次拉会出现「学院已选、年级还在转圈」的中间态。
 *
 * 不分页:字典量恒定(单校 10~30 个学院、5 个年级),结果是只读且几乎不变,
 * 由 stores/dict.js 做进程内缓存,一个会话只请求一次。
 *
 * @returns {Promise<{depts: Array, grades: Array}>}
 */
export function schoolDict() {
  return request.get(DICT_ENDPOINT.school)
}

/**
 * 学院 → 专业级联下拉(按学院 + 本专科过滤)。
 *
 * 与 schoolDict 分开:专业依赖「已选学院 + 已选本专科」两个前置值,
 * 不能合并进一次性的院系年级响应。用户每改一次学院或本专科才查一次,
 * 频率低、结果小,单独端点更贴合调用形态。
 *
 * @param {number} deptId 学院字典 id
 * @param {number} level  本专科 1=本科 2=专科
 * @returns {Promise<Array>} 专业字典项列表(id / name / code / level / sort)
 */
export function majors(deptId, level) {
  return request.get(DICT_ENDPOINT.majors, { params: { deptId, level } })
}

/**
 * 一次拉全校专业(挂载预取用)。
 *
 * 后端带 Redis 缓存(TTL 10min),返回该校启用中的全部专业(含 deptId / level),
 * 前端据此按「当前学院 + 本专科」即时过滤专业下拉,不再逐组合发请求。
 * 彻底消除级联竞态与「首次选完学院+本专科却没加载出来」的交互问题。
 *
 * @param {number} [schoolId] 学校 id,缺省取后端默认(恒 1)
 * @returns {Promise<Array>} 专业字典项列表(id / deptId / name / code / level / sort)
 */
export function allMajors(schoolId) {
  return request.get(DICT_ENDPOINT.majorsAll, {
    params: schoolId != null ? { schoolId } : undefined,
  })
}

/**
 * 写入学校归属(注册后补资料 / 单独修改)。
 *
 * 传的是**字典 id**,不是自由文本。
 *
 * 注意:**必须带 majorId**。后端 SchoolProfileDTO.majorId 是 @NotNull,
 * 少发这一个键会被 400「请选择专业」打回;本专科(level)无需传,
 * 它由 school_major.level 派生,不落 user 表。
 *
 * @param {{schoolId:number, deptId:number, gradeId:number, majorId:number}} payload
 */
export function saveSchoolProfile(payload) {
  return request.post('/user/school-profile', {
    [SCHOOL_PROFILE_PAYLOAD.schoolId]: payload.schoolId,
    campus: payload.campus ?? null,
    [SCHOOL_PROFILE_PAYLOAD.deptId]: payload.deptId,
    [SCHOOL_PROFILE_PAYLOAD.gradeId]: payload.gradeId,
    [SCHOOL_PROFILE_PAYLOAD.majorId]: payload.majorId,
  })
}

/**
 * 从用户资料对象里读出学院 / 年级 / 专业。
 *
 * 返回 `{ deptId, deptName, gradeId, gradeName, majorId }`,其中 name 的来源有三级:
 *   1. 后端直接下发的 name(权威,别的客户端写的数据也能正确显示)
 *   2. 按 id 去字典反查(后端没下发 name 时)
 *   3. 旧自由文本字段(仅存量数据)
 *
 * 三级兜底缺一不可:只做 1 则后端不下发 name 时全站显示空白;
 * 只做 2 则存量用户(id 为 null)显示不出任何东西。
 *
 * majorId 一定带出:设置页回填专业下拉要用它,后端 /user/me 已返回。
 * 本专科(level)不在这里读 —— user 表不存,由调用方用专业字典反查。
 *
 * @param {object} user 用户资料对象
 * @param {{depts:Array, grades:Array}} dict 字典(可传空数组)
 */
export function readIdentity(user, dict = { depts: [], grades: [] }) {
  const deptId = user?.[DICT_FIELDS.deptId] ?? null
  const gradeId = user?.[DICT_FIELDS.gradeId] ?? null
  const majorId = user?.[DICT_FIELDS.majorId] ?? null
  const gradeName =
    user?.[DICT_FIELDS.gradeName] ||
    dict.grades?.find((g) => Number(g.id) === Number(gradeId))?.name ||
    user?.[DICT_FIELDS.legacyGrade] ||
    ''
  // 大几换算输入:入学年份一律取年级字典的 enrollYear(openapi 禁止从 name 截取)
  const enrollYear = Number(
    dict.grades?.find((g) => Number(g.id) === Number(gradeId))?.enrollYear,
  ) || null
  /*
   * 大几口径:本科 4 年(gradeStage 默认)。专科 3 年在读第 4 年会显示「大四」
   * 而非「已毕业」——level 在 user 表不存、profile 页拿它要多一次专业字典
   * 反查,为一个 3 年制毕业生的角标不值得;等有大几的准确度投诉再补 level。
   */
  return {
    deptId,
    gradeId,
    majorId,
    enrollYear,
    /** 「大一'..'大四」/「已毕业」;信息不足(未填年级/字典缺)为空串 */
    stage: gradeStage(enrollYear, user?.level),
    deptName:
      user?.[DICT_FIELDS.deptName] ||
      dict.depts?.find((d) => Number(d.id) === Number(deptId))?.name ||
      user?.[DICT_FIELDS.legacyDept] ||
      '',
    gradeName,
  }
}

export default { schoolDict, majors, allMajors, saveSchoolProfile, readIdentity }