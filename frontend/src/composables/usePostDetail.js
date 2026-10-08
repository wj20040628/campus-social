import { computed } from 'vue'
import { resolveUrl } from '@/utils/resolve'

/**
 * 帖子详情页的纯计算层(PostDetail.vue.md)
 * ------------------------------------------------------------------
 * 这里全是**无副作用的派生数据**:媒体解析、网格列数、计数封顶、
 * 评论正文分段。它们和「取帖 / 发评论 / 点赞」没有共享状态,
 * 内联在 PostDetail.vue 里会把文件顶过 300 行门禁。
 */

/** 计数 999+ 封顶。ID 类数字不封顶(那不是计数,封顶会让人查不到记录) */
export function capCount(n) {
  const v = Number(n) || 0
  return v > 999 ? '999+' : String(v)
}

/**
 * 后端把 media 存成 JSON 字符串,历史数据里还有 null 与非数组的脏值。
 * 三种都要挡住 —— 直接 JSON.parse 的写法在脏数据上会抛错把整页带崩。
 */
export function parseMedia(raw) {
  let m = raw
  if (!m) return []
  if (typeof m === 'string') {
    try {
      m = JSON.parse(m)
    } catch {
      return []
    }
  }
  return Array.isArray(m) ? m : []
}

/**
 * 媒体区的列数档位(设计 §1 行 5)。
 * 1 图全宽 / 2 图双列 / ≥3 图三列。桌面端 ≥4 图改两列由 CSS 媒体查询接管。
 */
export function mediaCols(count) {
  if (count === 1) return 'one'
  if (count === 2) return 'two'
  return 'three'
}

/**
 * 把评论正文拆成片段,@昵称 若命中 mentioned(昵称→id)则变成可点跳主页的 link。
 *
 * 为什么用后端存的 昵称→id 映射而不是靠正文反查:昵称可能重名,
 * 「@张三」到底是哪个张三,只有后端知道。
 *
 * @param {{content:string, mentioned?:Record<string, number>}} c
 * @returns {Array<{text:string, link?:number}>}
 */
export function splitComment(c) {
  const text = c.content || ''
  const keys = Object.keys(c.mentioned || {})
  if (keys.length === 0) return [{ text }]

  const segs = []
  let i = 0
  let buf = ''
  while (i < text.length) {
    let hit = null
    for (const k of keys) {
      const marker = '@' + k
      if (!text.startsWith(marker, i)) continue
      // 边界:标记后必须到结尾或跟空白。昵称不含空格,所以匹配即可
      if (i + marker.length >= text.length || /\s/.test(text[i + marker.length])) {
        hit = { k, len: marker.length }
        break
      }
    }
    if (hit) {
      if (buf) segs.push({ text: buf })
      segs.push({ text: hit.k, link: c.mentioned[hit.k] })
      i += hit.len
      buf = ''
    } else {
      buf += text[i]
      i++
    }
  }
  if (buf) segs.push({ text: buf })
  // 兜底:正文里一个 @昵称 都没命中时 segs 是空的,
  // 此时返回空数组会让评论区渲染出空白条目 —— 比显示原文更糟
  return segs.length ? segs : [{ text }]
}

/**
 * 帖子的媒体派生数据。传 ref(post) 而不是 post.value,
 * 免得调用方在每次赋值后记得重算。
 *
 * @param {import('vue').Ref<object|null>} post
 */
export function usePostMedia(post) {
  const mediaList = computed(() => parseMedia(post.value?.media))
  const imgList = computed(() =>
    mediaList.value.filter((x) => x.type === 'img').map((x) => resolveUrl(x.url)),
  )
  const cols = computed(() => mediaCols(mediaList.value.length))
  /**
   * 图片在 imgList 里的序号。
   * alt 文案必须用「第几张图」而不是数组下标 —— 有视频时下标会与图片序号错位,
   * 读屏用户听到的「帖子附图 3」可能指第二张图。
   */
  const imgIndex = (m) => imgList.value.indexOf(resolveUrl(m.url)) + 1

  return { mediaList, imgList, cols, imgIndex }
}