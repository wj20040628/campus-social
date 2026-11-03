/**
 * 圈内角色（circle_member.role）文案的唯一出处
 * ------------------------------------------------------------------
 * 与后端一致：1 圈主 · 2 圈管理 · 3 普通成员
 * （升级窗、任命/撤销圈管的判断都在服务端，前端只负责把码翻成人话）。
 *
 * 【为什么有两套措辞】
 * role=2 在两种密度的 UI 里说法不同：
 *   - 管理面板（CircleManagePanel）：空间充裕，用「圈管理」—— 说清楚管辖范围
 *   - 成员行 / 广场行（CircleMemberRow / CircleRow）：一列小标签，用「管理」
 * 两套都刻意，短版从完整版派生、只覆写 role=2 一项，
 * 保证「圈主」和码的含义永远与完整版同源。
 *
 * 【为什么单独立文件】原先三处各写一份 if/表，没有东西保证不漂移 ——
 * 举报理由文案（用户报「色情」、管理端显示「广告营销」）就是这么漂移出来的。
 */

/** 完整版。管理面板等空间充裕处 */
export const CIRCLE_ROLE_TEXT = {
  1: '圈主',
  2: '圈管理',
  3: '成员',
}

/** 短版。成员行、广场行等窄标签处；只覆写偏长的 role=2 */
export const CIRCLE_ROLE_TEXT_SHORT = {
  ...CIRCLE_ROLE_TEXT,
  2: '管理',
}

/**
 * 取文案。普通成员传 3（或 undefined 走兜底）。
 * @param {number} role circle_member.role
 * @param {boolean} short 是否窄标签短版
 */
export function circleRoleLabel(role, short = false) {
  const table = short ? CIRCLE_ROLE_TEXT_SHORT : CIRCLE_ROLE_TEXT
  return table[role] || CIRCLE_ROLE_TEXT[3]
}