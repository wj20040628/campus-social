/**
 * 公告分类与状态常量(SPEC §6.1 / openapi 的 AnnouncementCategory)
 *
 * 为什么单独抽出来:分类字典原本在 Announcement.vue 里定义了一遍,
 * 后台管理页与编辑器又要再定义两遍。三处各写一份必然会漂移
 * (加一个「其他」漏改一处,前台能选后台不能选)。
 *
 * value 是契约值,**不重排** —— openapi 里 category 的 enum 是 1..6,
 * 前端下标当 key 用会在插值时静默错位。
 */

/** 1教务 2后勤 3活动 4社团 5紧急 6其他 */
export const CATEGORY = {
  1: '教务',
  2: '后勤',
  3: '活动',
  4: '社团',
  5: '紧急',
  6: '其他',
}

/** 筛选 chip 用:全部 + 六个分类 */
export const CATEGORY_CHIPS = [
  { label: '全部', value: null },
  ...Object.entries(CATEGORY).map(([value, label]) => ({ value: Number(value), label })),
]

/** 后台状态筛选 chip。null=全部 */
export const STATUS_CHIPS = [
  { label: '全部', value: null },
  { label: '已发布', value: 1 },
  { label: '草稿', value: 0 },
  { label: '已下架', value: 2 },
]

/**
 * 状态文案与视觉档位。
 * 视觉靠 chip 底色 + 文字双通道,不靠颜色单通道(色觉障碍用户读得出「已下架」三个字)。
 */
export const STATUS = {
  0: { label: '草稿', tone: 'draft' },
  1: { label: '已发布', tone: 'live' },
  2: { label: '已下架', tone: 'off' },
}

/** 置顶上限。超过时前端禁用置顶按钮并给原因(AdminAnnouncement.vue.md §3 错误态) */
export const MAX_TOP = 3
