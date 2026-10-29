/**
 * 校内认证 · 用户侧 API
 * ------------------------------------------------------------------
 * 契约来源:docs/api/openapi.yaml
 *   GET  /user/verify/status       当前认证状态(含 rejectReason)
 *   POST /user/verify/student-no   提交学号,进入人工核验
 *
 * [为什么这两个接口此前全项目 0 引用]
 * 后端早就实现了,契约里也写着,但前端一处都没调 —— 于是
 * 「用户提交完学号就失去了反馈」:不知道在等谁、不知道等了多久、
 * 被驳回了也不知道哪里填错。学号核验是本校唯一认证通道
 * (学校不发学生邮箱),这条链断在这里,前面所有功能都跟着废。
 */
import request from '@/utils/request'

/**
 * 当前认证状态。
 *
 * `rejectReason` 只在 verifyStatus=3 时有值,其余状态一律 null。
 *
 * @returns {Promise<{
 *   verifyStatus: 0|1|2|3,
 *   verifyStatusText?: string,
 *   schoolId?: number, schoolName?: string|null,
 *   verifiedAt?: string|null,
 *   rejectReason?: string|null,
 *   canStrangerChat: boolean,
 *   canPublishAnnouncement: boolean,
 *   profileComplete: boolean,
 * }>}
 */
export function verifyStatus() {
  return request.get('/user/verify/status')
}

/**
 * 提交学号(人工核验兜底通道)。
 *
 * 两个后端约束决定了前端必须做的校验:
 *  1. **学号库级唯一**:被**其他账号**占用返回 409 STUDENT_NO_EXISTS。
 *     被驳回后原学号已释放,本人重新提交同一个号不会冲突
 *     （唯一校验会排除自己）；改的是不是「正确的号」由六层校验判断。
 *  2. **状态 1/2 被拦**:已认证 / 审核中都不让再提交(409)。
 *     状态 3(驳回)与 0(未提交)放行 —— 这正是「重新提交」的通路。
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