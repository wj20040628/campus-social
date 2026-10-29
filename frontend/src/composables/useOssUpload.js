import request from '@/utils/request'
import { upload as fallbackUpload } from '@/api/feed'

/**
 * OSS 前端直传（直传链路 P4）。
 * ------------------------------------------------------------------
 * ## 流程
 * 1. `GET /upload/sts?ext=xxx` 拿 STS 临时令牌 + 对象 key + 回调参数。
 *    - 直传未开 / OSS 未配 → 后端回 503；未登录 → 回 401。
 *    - 503 一律降级到 `POST /upload`（服务端兜底，走本地盘或 STS 服务端上传）。
 *    - 401 不降级（POST /upload 同样要登录，降级只会再 401 一次，直接抛）。
 * 2. ali-oss 用 STS 直传对象 key，带 `x-oss-callback` / `x-oss-callback-var` 头。
 *    OSS 传完主动回调服务端做验签 + 魔数校验 + 落库归属。
 * 3. 回调成功后 OSS 回 200，客户端拿到回调 body（{"status":"ok"}）。
 *    最终 url 用服务端下发的 key 拼规范相对路径 `url = '/' + key`，
 *    与 `POST /upload` 落库的 `/uploads/...` 逐字一致，前端 resolveUrl 零改动。
 *
 * ## 为什么直传不返回 thumbUrl
 * 服务端 `POST /upload` 会在 Java 侧压缩出 `_t` 缩略图并回 thumbUrl；
 * 直传链路服务端只做「归属记录 + 安全校验」，不另存缩略图。
 * 但读链路 MediaController 的 `?t=1` 走 x-oss-process 实时缩略图，
 * 前端展示小图用 `resolveUrl(url + '?t=1')` 即可，无需存 thumbUrl。
 *
 * ## 降级与重试
 * - STS 令牌临期（ExpiredToken / InvalidAccessKeyId / 403）→ 重取一次 STS 再传。
 * - 其余 OSS 错误（网络抖动 / CORS / 回调 403 内容不符）→ 降级 POST /upload。
 *   服务端兜底上传有同样的魔数校验，内容真有问题会一致失败，不会静默放行。
 */

/** 从文件名 / mime 推出扩展名，供对象 key 后缀 + 后端魔数校验 + Content-Type */
function extOf(file) {
  const name = file.name || ''
  const dot = name.lastIndexOf('.')
  if (dot >= 0 && dot < name.length - 1) return name.slice(dot + 1).toLowerCase()
  const mime = file.type || ''
  if (mime.startsWith('image/')) return (mime.split('/')[1] || 'jpg').toLowerCase()
  if (mime.startsWith('video/')) return (mime.split('/')[1] || 'mp4').toLowerCase()
  return 'bin'
}

/** uploadHost = https://{bucket}.{endpoint} → 拆出 bucket 与 endpoint 给 ali-oss */
function parseHost(uploadHost) {
  const host = new URL(uploadHost).host // {bucket}.{endpoint}
  const idx = host.indexOf('.')
  return { bucket: host.slice(0, idx), endpoint: host.slice(idx + 1) }
}

function buildClient(OSS, sts, uploadHost) {
  const { bucket, endpoint } = parseHost(uploadHost)
  return new OSS({
    region: endpoint.split('.')[0], // oss-cn-hangzhou（仅用于构造 host，endpoint 优先）
    endpoint,
    accessKeyId: sts.accessKeyId,
    accessKeySecret: sts.accessKeySecret,
    stsToken: sts.securityToken,
    bucket,
    secure: true,
    timeout: 300000, // 视频最大 25MB，弱网可能要 2 分钟以上，与兜底上传同口径
  })
}

/** 真正的 OSS PUT（带回调头）。成功回 {url, kind} */
async function putDirect(file, stsMeta, kind, onProgress) {
  const { key, uploadHost, callback, callbackVar, sts } = stsMeta
  // ali-oss 仅在「真要走直传」时才动态加载：备案中直传关闭时每次都降级 POST /upload，
  // SDK 完全不下发，路由 chunk 也不会被它拖胖。
  const OSS = (await import('ali-oss')).default
  const client = buildClient(OSS, sts, uploadHost)
  const contentType = file.type || (kind === 'video' ? 'video/mp4' : 'image/jpeg')
  await client.put(key, file, {
    // 服务端已对 callback/callbackVar 做过 base64，直接塞头，OSS 回传服务端验签
    headers: {
      'Content-Type': contentType,
      'x-oss-callback': callback,
      'x-oss-callback-var': callbackVar,
    },
    // 大文件 ali-oss 内部自动走分片，后端 STS 策略已授权 ListParts / AbortMultipartUpload
    mime: contentType,
    // ali-oss 的 progress 给 0~1 的 fraction，换算成字节交给 UI
    progress: (p) => {
      if (typeof onProgress === 'function') {
        onProgress({ loaded: Math.round(file.size * p), total: file.size })
      }
    },
  })
  // 回调成功 → 客户端拿到 {"status":"ok"}；url 用服务端 key 拼规范相对路径
  return { url: '/' + key, kind, thumbUrl: undefined }
}

/** 取一次 STS（silent: 避免 503 触发全局红 toast，降级分支自己兜底提示） */
function fetchSts(ext) {
  return request.get('/upload/sts', { params: { ext }, silent: true })
}

/**
 * 统一上传入口：优先 OSS 直传，失败 / 未开自动降级服务端。
 * @param {File} file
 * @param {'image'|'video'} kind
 * @returns {Promise<{url:string, kind:string, thumbUrl?:string}>}
 */
export async function uploadToOss(file, kind = 'image', onProgress) {
  const ext = extOf(file)
  let stsMeta
  try {
    stsMeta = await fetchSts(ext)
  } catch (e) {
    if (e?.businessCode === 401) throw e // 未登录：不降级，交由调用方处理
    return fallbackUpload(file, kind, onProgress) // 直传不可用 → 服务端兜底
  }
  try {
    return await putDirect(file, stsMeta, kind, onProgress)
  } catch (e) {
    // STS 临期 / token 失效：刷新一次重试
    const why = (e?.name || '') + ' ' + (e?.message || '')
    if (/ExpiredToken|InvalidAccessKeyId|SecurityToken|403/.test(why)) {
      try {
        const fresh = await fetchSts(ext)
        return await putDirect(file, fresh, kind, onProgress)
      } catch (e2) {
        if (e2?.businessCode === 401) throw e2
        return fallbackUpload(file, kind, onProgress)
      }
    }
    // 其余 OSS 错误（网络 / CORS / 回调 403 内容不符）→ 降级服务端兜底
    return fallbackUpload(file, kind, onProgress)
  }
}

export default uploadToOss
