import { computed, ref } from 'vue'
import { upload as uploadApi } from '@/api/feed'
import { ElMessage } from '@/utils/feedback'

/** 单格媒体上限。超过时拒绝第 10 张并给出具体数字(Publish.vue.md §3) */
export const MAX_MEDIA = 9
/** 单文件体积上限 */
const MAX_IMAGE_BYTES = 7.5 * 1024 * 1024
const MAX_VIDEO_BYTES = 50 * 1024 * 1024

/**
 * 发帖的媒体上传状态机(Publish.vue.md §1 行 3)
 * ------------------------------------------------------------------
 * ## 为什么要按「格子」建模而不是按「media 数组」建模
 * 原实现只在 `media` 里 push 成功项,失败就一个全局 toast。后果:
 * 一次选 9 张、上传 3 张失败时,用户看到的是一次「上传失败」,
 * 不知道是哪三张,也不知道该不该重传 —— 只能全删重选。
 *
 * 现在每个格子有独立状态(uploading / done / failed),
 * 失败的格子留在原地等重传,已成功的格子不受影响。
 *
 * ## 三条不能省的语义
 * 1. **失败不丢 File 对象**。丢了就没法重传,只能让用户重新选文件。
 * 2. **并发上传全部发出**。串行的话 9 张图要等 9 个来回。
 * 3. **key 用自增序号而不是数组下标**。删除中间一格后下标会移位,
 *    用下标做 key 会让 Vue 复用错格子(第 3 格突然显示第 2 格的内容)。
 */
export function useMediaUpload() {
  let seq = 0
  const cells = ref([])

  const doneCount = computed(() => cells.value.filter((c) => c.state === 'done').length)
  const failedCount = computed(() => cells.value.filter((c) => c.state === 'failed').length)
  /** 已成功的媒体项,提交时直接给这个 —— 不要自己再 map 一次,容易漏字段 */
  const readyMedia = computed(() =>
    cells.value.filter((c) => c.state === 'done').map((c) => ({
      type: c.kind === 'video' ? 'video' : 'img',
      url: c.url,
    })),
  )

  /**
   * el-upload 的 before-upload:只做本地校验,不碰网络。
   * 返回 false 是 EP 约定的「拒绝本次文件」。
   */
  function beforeUpload(file) {
    const isImg = file.type.startsWith('image/')
    const isVideo = file.type.startsWith('video/')
    if (!isImg && !isVideo) {
      ElMessage.warning('仅支持图片或视频')
      return false
    }
    const max = isVideo ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES
    if (file.size > max) {
      ElMessage.warning(isVideo ? '视频不能超过 50MB' : '图片不能超过 7.5MB')
      return false
    }
    // 上限在「已选 + 正在传」的口径上算 —— 只数 done 会让用户连点选到 15 张
    if (cells.value.length >= MAX_MEDIA) {
      ElMessage.warning(`最多 ${MAX_MEDIA} 张图`)
      return false
    }
    return true
  }

  /**
   * el-upload 的 http-request:每个文件一次。
   * 失败不抛 —— 抛出去会被 EP 的全局错误处理吞成一句 toast,
   * 而我们要的是「这一格标红 + 可以重传」。
   */
  async function uploadFile(option) {
    const { file } = option
    const kind = file.type.startsWith('video/') ? 'video' : 'image'
    seq += 1
    const cell = { key: `m${seq}`, state: 'uploading', kind, file, url: '' }
    cells.value.push(cell)
    try {
      const data = await uploadApi(file, kind)
      cell.url = data.url
      cell.state = 'done'
      cell.file = null // 传完就释放,别把 File 挂在响应式对象里长期占内存
    } catch {
      cell.state = 'failed'
    }
  }

  /** 重传:复用同一个 File 对象,不要求用户重新选 */
  function retry(index) {
    const cell = cells.value[index]
    if (!cell || cell.state !== 'failed' || !cell.file) return
    cell.state = 'uploading'
    uploadFile({ file: cell.file }).catch(() => {
      /* uploadFile 内部已兜住,这里只是不让 unhandled rejection 冒到控制台 */
    })
  }

  function remove(index) {
    cells.value.splice(index, 1)
  }

  /** 编辑模式回填:后端只给已完成的项 */
  function seed(mediaList) {
    seq = 0
    cells.value = (Array.isArray(mediaList) ? mediaList : []).map((m) => {
      seq += 1
      return {
        key: `m${seq}`,
        state: 'done',
        kind: m.type === 'video' ? 'video' : 'image',
        url: m.url,
        file: null,
      }
    })
  }

  function reset() {
    cells.value = []
  }

  return {
    cells, doneCount, failedCount, readyMedia,
    beforeUpload, uploadFile, retry, remove, seed, reset,
  }
}

/**
 * 话题串 -> 话题数组(Publish.vue.md §1 行 4/5)
 *
 * 上限 5 个、单个 64 字,都按设计走。超出时**截断而不是报错** ——
 * 用户可能一次粘贴了 8 个话题,直接拒绝整条输入比帮他留前 5 个更糟。
 */
export function parseTopics(text) {
  const seen = new Set()
  const out = []
  for (const raw of String(text || '').split(/[,，\s]+/)) {
    const t = raw.trim().replace(/^#+/, '').replace(/#+$/, '').trim()
    if (!t || t.length > 64 || seen.has(t)) continue
    seen.add(t)
    out.push(t)
    if (out.length >= 5) break
  }
  return out
}