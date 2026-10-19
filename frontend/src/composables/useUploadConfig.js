import { ref } from 'vue'
import { uploadConfig as uploadConfigApi } from '@/api/feed'

/**
 * 上传能力探测(共享缓存):拉一次 /upload/config,拿到
 * - videoEnabled:视频功能总开关(false=前端隐藏所有视频入口)
 * - maxVideoBytes / maxImageBytes:单文件上限(本地校验,超了拒,不发无效请求)
 *
 * 为什么做成模块级单例:发帖(Publish)与圈子发帖(CircleComposer)两处都会
 * 探测,各自拉一次就 2 个请求;挂到模块上缓存,谁先触发谁拉,后续复用。
 * 失败不致命 —— 拉不到就保持「允许视频、用默认 25MB」的乐观值,功能不崩。
 *
 * `limits` 是响应式 ref:模板/计算属性里直接读 `limits.videoEnabled` 等,
 * 探测回来后自动更新,不用各组件再各存一份。
 */

// 默认值:与 application.yml 的 upload.max-* 对齐(video 25MB / image 7.5MB)。
// 探测成功前先用这套,避免闪烁。
const DEFAULTS = {
  videoEnabled: true,
  maxVideoBytes: 25 * 1024 * 1024,
  maxImageBytes: 7.5 * 1024 * 1024,
}

export const limits = ref({ ...DEFAULTS })
let loading = null

export function ensureUploadConfig() {
  if (loading) return loading
  loading = uploadConfigApi()
    .then((data) => {
      if (data && typeof data === 'object') {
        limits.value = {
          videoEnabled: data.videoEnabled ?? DEFAULTS.videoEnabled,
          maxVideoBytes: data.maxVideoBytes ?? DEFAULTS.maxVideoBytes,
          maxImageBytes: data.maxImageBytes ?? DEFAULTS.maxImageBytes,
        }
      }
    })
    .catch(() => {
      /* 探测失败保持默认,视频入口仍可用,只是上限走默认值 */
    })
    .finally(() => {
      loading = null
    })
  return loading
}

export default ensureUploadConfig
