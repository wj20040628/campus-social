import request from '@/utils/request'
import { DICT_ENDPOINT, DICT_FIELDS, SCHOOL_PROFILE_PAYLOAD } from '@/constants/schoolDict'

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
 * 写入学校归属(注册后补资料 / 单独修改)。
 *
 * 传的是**字典 id**,不是自由文本。
 *
 * @param {{schoolId:number, deptId:number, gradeId:number}} payload
 */
export function saveSchoolProfile(payload) {
  return request.post('/user/school-profile', {
    [SCHOOL_PROFILE_PAYLOAD.schoolId]: payload.schoolId,
    [SCHOOL_PROFILE_PAYLOAD.deptId]: payload.deptId,
    [SCHOOL_PROFILE_PAYLOAD.gradeId]: payload.gradeId,
  })
}

/**
 * 从用户资料对象里读出学院 / 年级。
 *
 * 返回 `{ deptId, deptName, gradeId, gradeName }`,其中 name 的来源有三级:
 *   1. 后端直接下发的 name(权威,别的客户端写的数据也能正确显示)
 *   2. 按 id 去字典反查(后端没下发 name 时)
 *   3. 旧自由文本字段(仅存量数据)
 *
 * 三级兜底缺一不可:只做 1 则后端不下发 name 时全站显示空白;
 * 只做 2 则存量用户(id 为 null)显示不出任何东西。
 *
 * @param {object} user 用户资料对象
 * @param {{depts:Array, grades:Array}} dict 字典(可传空数组)
 */
export function readIdentity(user, dict = { depts: [], grades: [] }) {
  const deptId = user?.[DICT_FIELDS.deptId] ?? null
  const gradeId = user?.[DICT_FIELDS.gradeId] ?? null
  return {
    deptId,
    gradeId,
    deptName:
      user?.[DICT_FIELDS.deptName] ||
      dict.depts?.find((d) => Number(d.id) === Number(deptId))?.name ||
      user?.[DICT_FIELDS.legacyDept] ||
      '',
    gradeName:
      user?.[DICT_FIELDS.gradeName] ||
      dict.grades?.find((g) => Number(g.id) === Number(gradeId))?.name ||
      user?.[DICT_FIELDS.legacyGrade] ||
      '',
  }
}

export default { schoolDict, saveSchoolProfile, readIdentity }