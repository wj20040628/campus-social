import { computed, reactive, ref } from 'vue'
import { uploadToOss } from '@/composables/useOssUpload'
import { ElMessage } from '@/utils/feedback'
import { ensureUploadConfig, limits } from '@/composables/useUploadConfig'
import { isHevcFile } from '@/utils/videoCodec'
import { createVideoPoster } from '@/utils/videoPoster'

/** 单格媒体上限。超过时拒绝第 10 张并给出具体数字(Publish.vue.md §3) */
export const MAX_MEDIA = 9

/** 单文件体积上限:走后端 /upload/config 探测(useUploadConfig 单例缓存)。
 *  探测没回来前用默认值(video 25MB / image 7.5MB,与 application.yml 对齐)。
 *  暴露为 computed,让调用方(模板 v-if / beforeUpload)能响应式读到。 */
const MAX_IMAGE_BYTES = computed(() => limits.value.maxImageBytes)
const MAX_VIDEO_BYTES = computed(() => limits.value.maxVideoBytes)
/** 视频功能总开关:false 时 Publish 隐藏视频按钮,本地校验直接拒收视频 */
const VIDEO_ENABLED = computed(() => limits.value.videoEnabled)

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
  // 挂上能力探测:首次进入发帖页就拉 /upload/config,拿到 videoEnabled + 上限
  ensureUploadConfig()
  let seq = 0
  const cells = ref([])

  const doneCount = computed(() => cells.value.filter((c) => c.state === 'done').length)
  const failedCount = computed(() => cells.value.filter((c) => c.state === 'failed').length)
  const uploadingCount = computed(() => cells.value.filter((c) => c.state === 'uploading').length)
  /** 已成功的媒体项,提交时直接给这个 —— 不要自己再 map 一次,容易漏字段 */
  const readyMedia = computed(() =>
    cells.value.filter((c) => c.state === 'done').map((c) => ({
      type: c.kind === 'video' ? 'video' : 'img',
      url: c.url,
      // 视频封面:移动端浏览器不预加载视频,没有 poster 就是一块黑
      ...(c.poster ? { poster: c.poster } : {}),
    })),
  )

  /**
   * el-upload 的 before-upload:只做本地校验,不碰网络。
   * 返回 false 是 EP 约定的「拒绝本次文件」。
   */
  async function beforeUpload(file) {
    const isImg = file.type.startsWith('image/')
    const isVideo = file.type.startsWith('video/')
    // 视频功能总开关关掉时,视频直接拒(后端也会拦,前端先给个明确提示)
    if (isVideo && !VIDEO_ENABLED.value) {
      ElMessage.warning('视频功能已暂停,请改用图片')
      return false
    }
    if (!isImg && !isVideo) {
      ElMessage.warning(VIDEO_ENABLED.value ? '仅支持图片或视频' : '仅支持图片')
      return false
    }
    // HEVC 预检:浏览器普遍不支持 H.265,传上去也播不了。提前拦比传完失败友好。
    // 与后端 VideoCodecUtil 同判据;后端也会再拦一次(防直传绕过)。
    if (isVideo && (await isHevcFile(file))) {
      ElMessage.warning('视频编码不支持(H.265/HEVC)，请用 H.264 编码的 mp4 重新上传')
      return false
    }
    const max = isVideo ? MAX_VIDEO_BYTES.value : MAX_IMAGE_BYTES.value
    if (file.size > max) {
      ElMessage.warning(
        isVideo
          ? `视频不能超过 ${Math.round(MAX_VIDEO_BYTES.value / 1024 / 1024)}MB`
          : `图片不能超过 ${Math.round(MAX_IMAGE_BYTES.value / 1024 / 1024)}MB`,
      )
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
   * 对【已存在的】格子执行上传:成功/失败都只改这一格。
   *
   * 抽出来的两个原因:
   * 1. 首次上传与「失败重传」共用同一段上传逻辑;
   * 2. 重传必须复用原格,不能走 uploadFile —— 那会 append 一个新格。
   */
  async function runUpload(cell) {
    const startedAt = Date.now()
    cell.progress = 0
    cell.loadedBytes = 0
    cell.totalBytes = cell.file?.size || 0
    cell.etaSec = null
    // 视频封面与上传并行截取:20MB 视频传 20 秒,截帧通常 1 秒内就完成
    const posterTask = cell.kind === 'video' && cell.file ? createVideoPoster(cell.file) : null
    try {
      // 优先 OSS 直传,失败/未开启自动降级服务端 POST /upload;
      // 直传成功代表 OSS 回调已落库归属,返回的 url 即规范相对路径
      const data = await uploadToOss(cell.file, cell.kind, ({ loaded, total }) => {
        const elapsed = (Date.now() - startedAt) / 1000
        cell.loadedBytes = loaded
        cell.totalBytes = total || cell.totalBytes
        // 到 100% 后服务端还要落盘/转存,先停在 99% 显示「处理中」,避免假完成
        cell.progress = cell.totalBytes
          ? Math.min(99, Math.floor((loaded / cell.totalBytes) * 100))
          : 0
        // 前 1.5 秒的速度包含建连/STS 开销,估出来偏慢,不展示
        if (elapsed >= 1.5 && loaded > 0 && cell.totalBytes > loaded) {
          const speed = loaded / elapsed
          cell.etaSec = Math.max(1, Math.round((cell.totalBytes - loaded) / speed))
        }
      })
      // 视频封面:截帧成功就按图片上传,拿到的 url 随 media JSON 一起落库。
      // 失败不阻塞发布,旧帖/失败时前端仍有 #t 首帧兜底。
      if (posterTask) {
        const posterFile = await posterTask
        if (posterFile) {
          try {
            const p = await uploadToOss(posterFile, 'image')
            cell.poster = p.url
          } catch {
            /* 封面失败不影响视频本身 */
          }
        }
      }
      cell.url = data.url
      cell.progress = 100
      cell.state = 'done'
      cell.file = null // 传完就释放,别把 File 挂在响应式对象里长期占内存
    } catch {
      cell.state = 'failed'
    }
  }

  /**
   * el-upload 的 http-request:每个文件一次,新建一格再上传。
   * 失败不抛 —— 抛出去会被 EP 的全局错误处理吞成一句 toast,
   * 而我们要的是「这一格标红 + 可以重传」。
   */
  async function uploadFile(option) {
    const { file } = option
    const kind = file.type.startsWith('video/') ? 'video' : 'image'
    seq += 1
    // 必须是 reactive:格子的 state/url 会在 await 之后才改。
    // 若用普通对象,直接改原始对象不经过 Proxy,不会触发重渲染 ——
    // 表现是「格子一直停在『上传中』转圈、图片永不出现」,但数据其实已就绪
    // (能点发布、发出来的帖子里图片也正常),从后端日志完全看不出来。
    const cell = reactive({
      key: `m${seq}`,
      state: 'uploading',
      kind,
      file,
      url: '',
      progress: 0,
      loadedBytes: 0,
      totalBytes: file.size || 0,
      etaSec: null,
      poster: '',
    })
    cells.value.push(cell)
    await runUpload(cell)
  }

  /** 重传:复用同一个 File 对象与同一个格子,不 append 新格、不要求用户重新选 */
  function retry(index) {
    const cell = cells.value[index]
    if (!cell || cell.state !== 'failed' || !cell.file) return
    cell.state = 'uploading'
    runUpload(cell)
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
        poster: m.poster || '',
        file: null,
      }
    })
  }

  function reset() {
    cells.value = []
  }

  return {
    cells, doneCount, failedCount, uploadingCount, readyMedia,
    beforeUpload, uploadFile, retry, remove, seed, reset,
    /** 视频功能总开关(响应式):false 时 UI 隐藏视频按钮 */
    videoEnabled: VIDEO_ENABLED,
    maxImageBytes: MAX_IMAGE_BYTES,
    maxVideoBytes: MAX_VIDEO_BYTES,
  }
}

/**
 * 单个话题名的长度上限。
 *
 * <p>与后端 {@code PostTopicBinder.TOPIC_NAME_MAX} **必须保持一致**：后端超长是静默丢弃，
 * 前端若不在这里拦，用户会看到「我明明输了这个话题、发出去却没了」，
 * 而且没有任何报错可查。15 字是展示侧的限制（胶囊要放得下）。
 */
export const TOPIC_NAME_MAX = 15

/**
 * 话题串 -> 话题数组(Publish.vue.md §1 行 4/5)
 *
 * 上限 5 个、单个 {@link TOPIC_NAME_MAX} 字。超出的**截断而不是报错** ——
 * 用户可能一次粘贴了 8 个话题,直接拒绝整条输入比帮他留前 5 个更糟。
 *
 * max 可传 Infinity 拿全量:Publish 的「+N」必须和 chips 用同一套规则
 * 数出来(都去重),否则「显示了 5 个、还有 N 个」会和实际话题数对不上。
 */
export function parseTopics(text, max = 5) {
  const seen = new Set()
  const out = []
  for (const raw of String(text || '').split(/[,，\s]+/)) {
    const t = raw.trim().replace(/^#+/, '').replace(/#+$/, '').trim()
    if (!t || t.length > TOPIC_NAME_MAX || seen.has(t)) continue
    seen.add(t)
    out.push(t)
    if (out.length >= max) break
  }
  return out
}

/**
 * 被丢弃的话题名（太长 / 重复之外的原因），供发帖页提示用户。
 *
 * <p>存在的理由：`parseTopics` 对超长话题是静默跳过的。若不单独给出这份清单，
 * 用户在话题框里输了一个 20 字的话题，chips 里不出现、发出去也没有，
 * 既没有任何提示也不知道为什么 —— 属于「以为成功了的失败」，最难排查。
 *
 * @returns {string[]} 因超长被丢弃的话题（已去重、已去 # 与空白）
 */
export function droppedTopics(text) {
  const seen = new Set()
  const out = []
  for (const raw of String(text || '').split(/[,，\s]+/)) {
    const t = raw.trim().replace(/^#+/, '').replace(/#+$/, '').trim()
    if (!t) continue
    if (t.length <= TOPIC_NAME_MAX) continue
    if (seen.has(t)) continue
    seen.add(t)
    out.push(t)
  }
  return out
}