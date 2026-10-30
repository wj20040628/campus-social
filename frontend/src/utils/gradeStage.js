/**
 * 年级 → 「大几」换算(2026-10-25)。
 *
 * 学年换算规则:入学年 9 月入学,自然年 − 入学年 + 1 恰好等于年级数,
 * 无需 9 月修正——验证:2023 级在 2026.10(第 4 学年上) = 大四(对);
 * 在 2026.03(第 4 学年下) = 大四(对);2026 级在 2026.09 = 大一(对)。
 *
 * 学制:本科 4 年、专科 3 年(与 school_major.level 同口径:1 本科 2 专科);
 * 超出学制显示「已毕业」。enrollYear 一律取自年级字典的 enrollYear 字段,
 * 不从 "2023级" 名称截取(字典已带该字段)。
 */

const CN_NUM = ['零', '一', '二', '三', '四', '五', '六', '七', '八']

/**
 * @param {number|null} enrollYear 入学年(字典 enrollYear)
 * @param {number|null} level 1本科 2专科;空按本科兜底
 * @param {Date} [now] 供测试注入
 * @returns {string} '大一'..'大六' | '已毕业' | ''(信息不足,不显示)
 */
export function gradeStage(enrollYear, level, now = new Date()) {
  const enroll = Number(enrollYear)
  if (!enroll || enroll < 2000 || enroll > 2100) return ''
  const stage = now.getFullYear() - enroll + 1
  if (stage < 1) return '' // 入学年写错(未来),不显示比显示错好
  const maxStage = level === 2 ? 3 : 4
  if (stage > maxStage) return '已毕业'
  return '大' + (CN_NUM[stage] || stage)
}
