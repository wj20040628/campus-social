import { recommendUsers, recommendCircles } from '@/api/onboarding'

/**
 * 发现页 API(计划 §6 场景3)。
 *
 * 当前复用引导的推荐端点:
 *   - 同好的人:`/onboarding/recommend-users` 本就按 `common_tag_count`(与当前用户标签重合数)打分,
 *     并返回「你们都喜欢 N 个标签」理由 —— 即「按兴趣找搭子」的真实召回。
 *   - 热门圈子:`/onboarding/recommend-circles` 当前按成员数排序(库里尚无 circle_tag 关系表,
 *     按标签推圈子需后续改表,见 §6 场景2)。
 *
 * 后端补 `/discover` 专属端点后,只改本文件,页面无需改动。
 */

/** 按当前用户兴趣推荐的人。@returns { users: RecommendUserItem[], emptyHint } */
export function discoverUsers(size = 12) {
  return recommendUsers(size)
}

/** 热门圈子(暂无圈子标签,退化为按成员数)。@returns { circles: RecommendCircleItem[], emptyHint } */
export function discoverCircles(size = 8) {
  return recommendCircles(size)
}

export default { discoverUsers, discoverCircles }
