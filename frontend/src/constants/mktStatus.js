/**
 * 集市活动投放状态（MktActivity.status）文案的唯一出处
 * ------------------------------------------------------------------
 * 与后端 MktStatus 枚举同值：LAUNCHING · FULL · PAUSED · EXPIRED
 * （由 MktActivityVoAssembler 按 isFull/isEnable/expireTime 推导下发）。
 *
 * 【为什么有两套措辞】
 * 同一个状态出现在两种密度的 UI 里：
 *   - 详情页右栏（useMktAside）：空间充裕，用完整说法「已满员 / 已暂停 / 已过期」
 *   - 我的发布列表（MktCard 状态灯）：一列小灯 + 短文字，用短说法「满员 / 暂停 / 过期」
 * 两套都是刻意的，抹平任何一个都损失可读性 —— 但 FULL 一档的语义必须一致，
 * 所以短版只覆写「字数」，不改「含义」，从完整版派生。
 *
 * 【为什么单独立文件】原先两处各写一份字面量表，没有东西保证
 * LAUNCHING 的文案、或码与文案的对应关系不漂移 —— 举报理由文案
 * （用户报「色情」、管理端显示「广告营销」）就是这么漂移出来的。
 */

/** 完整版。详情页右栏等空间充裕处 */
export const MKT_STATUS_TEXT = {
  LAUNCHING: '投放中',
  FULL: '已满员',
  PAUSED: '已暂停',
  EXPIRED: '已过期',
}

/**
 * 状态灯短版（MktCard「我的发布」）。
 * 只覆写偏长的三项，LAUNCHING 沿用完整版 —— 四档语义永远与上面那份同源。
 */
export const MKT_STATUS_TEXT_LAMP = {
  ...MKT_STATUS_TEXT,
  FULL: '满员',
  PAUSED: '暂停',
  EXPIRED: '过期',
}

/** 兜底：后端将来加了新状态而前端未跟上时，显示原始码而不是空白 */
export const MKT_STATUS_FALLBACK = '投放中'

/**
 * 取文案。
 * @param {string} s MktActivity.status
 * @param {boolean} lamp 是否状态灯短版
 */
export function mktStatusLabel(s, lamp = false) {
  const table = lamp ? MKT_STATUS_TEXT_LAMP : MKT_STATUS_TEXT
  return table[s] || MKT_STATUS_FALLBACK
}