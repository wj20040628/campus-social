/**
 * 集市活动(mkt)前端共享常量
 * ------------------------------------------------------------------
 * 一级分类与后端 MktActivityServiceImpl.CATEGORIES 保持 9 选 1 一致;
 * 投放时长快捷选项 1/3/6/12h,与计划 §发布页一致。
 */

/** 一级分类(9 选 1,必填)。与后端白名单逐字一致,不要改顺序/措辞。 */
export const CATEGORIES = [
  '游戏组队', '跑腿代办', '拼车出行', '旅游结伴', '学习搭子',
  '运动约伴', '干饭搭子', '闲置互助', '其它',
]

/** 投放时长快捷选项(小时)。自填范围 1–12,上限与后端 MAX_LAUNCH_HOURS 一致。 */
export const DURATION_OPTIONS = [1, 3, 6, 12]

/** 单次投放时长上限(小时),校验与后端一致。 */
export const MAX_LAUNCH_HOURS = 12

/**
 * 热门游戏(仅当一级分类 = 游戏组队 时可选)。
 * 选中的游戏写入 subTag,卡片/详情主文案展示为「游戏组队 · <游戏>」。
 */
export const HOT_GAMES = [
  '王者荣耀', '和平精英', '三角洲行动', 'PUBG', '原神',
  '英雄联盟', '永劫无间', '暗区突围', '蛋仔派对', '金铲铲之战',
]

export default { CATEGORIES, DURATION_OPTIONS, MAX_LAUNCH_HOURS, HOT_GAMES }
