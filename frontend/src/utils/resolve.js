// 资源 URL 解析:后端上传返回的是相对路径(如 /uploads/20260930/xxx.jpg),
// 前端展示前统一拼成绝对 URL。绝对路径(http...)原样返回,空值返回 ''。
export function resolveUrl(url) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  return window.location.origin + url
}

/**
 * 缩略图 URL(降本⑦):列表 / 头像等小图展示走 OSS 实时缩略图(?t=1),省下行流量。
 *
 * 只对本地 /uploads/ 路径生效 —— MediaController 收到 ?t=1 优先用已存的 _t 实体,
 * 没有则 x-oss-process 实时出 480px 缩略图(花图片处理费);本地 dev 无处理能力时优雅回退主图。
 * 外部 http 图(如 OAuth 头像)、视频不动:视频不走 image/resize,?t=1 会失效。
 */
export function resolveThumb(url) {
  if (!url) return ''
  if (url.startsWith('/uploads/')) return resolveUrl(url + '?t=1')
  return resolveUrl(url)
}

/**
 * 视频首帧预览。在 URL 后加 #t=0.1，让浏览器加载元数据后 seek 到 0.1s 渲染一帧；
 * 不加的话 feed 里 <video> 在点播放前是一块黑色（用户以为视频坏了）。
 * 已带 fragment 的 URL 原样返回，避免拼出两个 #。
 */
export function resolveVideo(url) {
  const u = resolveUrl(url)
  if (!u || u.includes('#')) return u
  // 取 1s 处而不是 0.1s:不少视频开头是黑场/淡入,0.1s 截出来仍是黑的。
  // 新上传的视频有 poster 会直接盖住它,这里只兜底旧帖子。
  return u + '#t=1'
}

export default { resolveUrl, resolveThumb, resolveVideo }
