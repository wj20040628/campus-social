import request from '@/utils/request'
import { SCHOOL_PROFILE_PAYLOAD } from '@/constants/schoolDict'

/**
 * 新用户引导 API(F11 / SPEC §5.5)
 * 契约来源:docs/api/openapi.yaml
 *
 * 注意 tag.js 里的 GET /tags 与本文件的 GET /onboarding/tags 是两个不同端点:
 * 前者返回全量字典并按 category 分组;后者做「首次梯度」裁剪,
 * 保证引导第一屏先看到本校最热的标签而不是一份 60 条完整字典。
 */

/**
 * 引导候选标签。
 * @returns { tags: TagItem[], minSelect, maxSelect }
 *         minSelect/maxSelect 由后端下发(3/5),前端不写死 ——
 *         写死就是「沉默逻辑错误」:后端调整策略后前端会拦错人数。
 */
export function tags(size = 60) {
  return request.get(`/onboarding/tags?size=${size}`)
}

/**
 * 完成引导(批量写学校归属 + 标签 + 关注,幂等)。
 *
 * **每一步都要调**(Onboarding.vue.md §7):
 *   第 1 步「下一步」→ 调(标签空)
 *   第 2 步「下一步」/「以后再选」→ 调
 *   第 3 步「进友声」/「先逛逛」→ 调
 * 只在最后一步调是错的:用户中途杀掉 App,后端没有 complete 记录,
 * 下次进首页又被守卫拦回引导页,陷入死循环。
 *
 * 幂等键是用户维度,重复提交返回 duplicated=true,不重复写。
 *
 * 学校归属传的是**字典 id**(deptId / gradeId / majorId),不是自由文本 ——
 * 自由文本会让「计算机学院」与「计科」算两个院系,同院系推荐(AC-14)漏人。
 * 键名从 SCHOOL_PROFILE_PAYLOAD 出,不写字面量。
 *
 * @param {{schoolId:number, deptId:number, gradeId:number, majorId:number,
 *          tagIds:number[], followUserIds?:number[], circleIds?:number[]}} payload
 */
export function complete(payload, opts = {}) {
  const { schoolId = 1, campus = null, deptId, gradeId, majorId, tagIds, followUserIds = [], circleIds = [] } = payload
  return request.post('/onboarding/complete', {
    [SCHOOL_PROFILE_PAYLOAD.schoolId]: schoolId,
    // 校区与院系解耦（2026-10-26）：之前这里把它丢了，用户在引导页选的校区存不进库
    campus,
    [SCHOOL_PROFILE_PAYLOAD.deptId]: deptId,
    [SCHOOL_PROFILE_PAYLOAD.gradeId]: gradeId,
    [SCHOOL_PROFILE_PAYLOAD.majorId]: majorId,
    tagIds,
    followUserIds,
    circleIds,
  }, opts)
}

/**
 * 推荐关注的人。规则排序,不上算法(PRD §9 排除 AI 匹配)。
 * @returns { users: RecommendUserItem[], emptyHint }
 *         emptyHint 由服务端下发以保证前后端空态文案一致(AC-19)。
 */
export function recommendUsers(size = 12) {
  return request.get(`/onboarding/recommend-users?size=${size}`)
}

/**
 * 推荐圈子。@returns { circles: RecommendCircleItem[], emptyHint }
 * 冷启动期允许返回空数组,空态由前端展示引导文案(不得出现空白页)。
 */
export function recommendCircles(size = 8) {
  return request.get(`/onboarding/recommend-circles?size=${size}`)
}

export default { tags, complete, recommendUsers, recommendCircles }