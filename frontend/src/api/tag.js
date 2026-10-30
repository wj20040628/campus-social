import request from '@/utils/request'

/**
 * 兴趣标签 API(SPEC §5.2)
 * 契约来源:docs/api/openapi.yaml
 *
 * 本轮「实现」标签体系(不是 MVP+),因为 F11 引导第 2 步要用。
 * 但「按标签找推荐」的发现页延后 —— 本文件不含任何 buddy 端点
 * (SPEC §2.2 明确禁止在 Phase 3 实现 buddy 的 Service/Controller/页面)。
 */

/**
 * 标签树(按 category 分组)。
 * 不分页:字典量恒定 30-60 条,结果应缓存。
 * @returns { groups: [{category, categoryName, tags: TagItem[]}], total }
 */
export function tree(category = null) {
  const q = category ? `?category=${category}` : ''
  return request.get(`/tags${q}`)
}

/**
 * 设置我的兴趣标签。语义是**全量覆盖**,不是增量追加。
 * 上限 8 个,超出返回 422 message「兴趣标签最多选择 8 个」。
 * @param {number[]} tagIds 最终集合
 */
export function replaceMine(tagIds) {
  return request.put('/user/tags', { tagIds })
}

/**
 * 查看他人兴趣标签。公开信息,任意登录用户可查。
 * 该页不得出现真实姓名与学号,只展示昵称+头像+标签。
 */
export function ofUser(userId) {
  return request.get(`/user/${userId}/tags`)
}

export default { tree, replaceMine, ofUser }