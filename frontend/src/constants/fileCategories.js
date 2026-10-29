/**
 * 文件库分类与状态文案（2026-10-27）。
 *
 * 后端只存 code（file_asset.category），文案只在这里出现一次；
 * 和 constants/reportReason.js 同一纪律，避免「后端一个值、页面另一套话」。
 */
export const FILE_CATEGORIES = [
  { value: 'template', label: '模板' },
  { value: 'courseware', label: '课件讲义' },
  { value: 'guide', label: '指导书' },
  { value: 'report', label: '实验报告' },
  { value: 'exam', label: '考试真题' },
  { value: 'other', label: '其他' },
]

export const FILE_CATEGORY_TEXT = Object.fromEntries(
  FILE_CATEGORIES.map((c) => [c.value, c.label])
)

export function fileCategoryLabel(code) {
  return FILE_CATEGORY_TEXT[code] || '其他'
}

export const FILE_STATUS = {
  0: '审核中',
  1: '已通过',
  2: '未通过',
  3: '已下架',
}

export function fileStatusLabel(status) {
  return FILE_STATUS[status] || '未知状态'
}

/** 可上传扩展名：与后端 FileDocMagic 同口径，zip 不收 */
export const FILE_ACCEPT = '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.txt,.md'

/**
 * 可在线预览的格式：与后端 FilePreview 同口径。
 * 只收浏览器能原生渲染的；Word/PPT/Excel 需下载后用本地软件打开。
 */
export const PREVIEWABLE_EXTS = ['pdf', 'txt', 'md']

export function isPreviewable(ext) {
  return PREVIEWABLE_EXTS.includes(String(ext || '').toLowerCase())
}

export function fileExtText(ext) {
  return ext ? String(ext).toUpperCase() : 'FILE'
}

/** 字节 → 1.2MB / 860KB。文件都 ≤3MB，不需要 GB 分支 */
export function formatBytes(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)}KB`
  return `${(n / 1024 / 1024).toFixed(1)}MB`
}
