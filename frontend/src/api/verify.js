/**
 * 校园信息 · 用户侧 API
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml
 *   GET  /user/verify/status       当前资料状态(含 rejectReason)
 *   POST /user/verify/student-no   提交学号：一致直接登记，不一致转核对
 *
 * 2026-10-30 起校园信息是**可选资料**，不授予任何功能权限。
 */
import request from '@/utils/request'

/**
 * 当前校园信息状态。
 *
 * `rejectReason` 只在 verifyStatus=3 时有值,其余状态一律 null。
 *
 * @returns {Promise<{
 *   verifyStatus: 0|1|2|3,
 *   verifyStatusText?: string,
 *   schoolId?: number, schoolName?: string|null,
 *   verifiedAt?: string|null,
 *   rejectReason?: string|null,
 *   profileComplete: boolean,
 * }>}
 */
export function verifyStatus() {
  return request.get('/user/verify/status')
}

/**
 * 提交学号。
 *
 * 两个后端约束决定了前端必须做的校验:
 *  1. **学号库级唯一**:被**其他账号**占用返回 409 STUDENT_NO_EXISTS。
 *     被驳回后原学号已释放,本人重新提交同一个号不会冲突。
 *  2. **状态 1/2 被拦**:已登记 / 核对中都不让再提交(409)。
 *     状态 3(需修改)与 0(未填写)放行 —— 这正是「重新提交」的通路。
 *
 * 格式合法后与所选年级/专业做一致性比对：
 * 一致直接登记（1），不一致转核对（2），都不会被硬拒。
 *
 * @param {{studentNo: string}} payload
 *        studentNo 学号(姓名后两位已不再收集)
 * @returns {Promise<{verifyStatus:number, status?:string}>}
 */
export function submitStudentNo({ studentNo }, opts = {}) {
  return request.post('/user/verify/student-no', {
    studentNo: (studentNo || '').trim(),
  }, opts)
}

export default {
  verifyStatus,
  submitStudentNo,
}