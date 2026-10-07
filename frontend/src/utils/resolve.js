// 资源 URL 解析:后端上传返回的是相对路径(如 /uploads/20260930/xxx.jpg),
// 前端展示前统一拼成绝对 URL。绝对路径(http...)原样返回,空值返回 ''。
export function resolveUrl(url) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  return window.location.origin + url
}

export default { resolveUrl }
