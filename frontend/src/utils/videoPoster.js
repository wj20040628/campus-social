/**
 * 从本地视频文件截一张封面图，上传时随帖子一起存。
 *
 * 为什么必须自己截：移动端浏览器（夸克/微信/部分 Chrome）在 feed 里
 * 不会为 <video> 预加载并绘制首帧，`#t=0.1` 也不保证生效，表现就是一块黑色。
 * poster 是 <img> 语义，不依赖视频解码，任何浏览器都会显示。
 *
 * 取帧策略：0.5s / 25% / 50% 三个点各截一次，取亮度最高的一帧，
 * 避免把黑场/淡入的第一帧当封面。全部过暗或解码失败返回 null，
 * 调用方继续发布视频，只是没有封面（不阻塞上传）。
 */

const DARK_LUM = 12
const GOOD_LUM = 24

function once(el, event, timeoutMs) {
  return new Promise((resolve, reject) => {
    const on = () => {
      clearTimeout(timer)
      el.removeEventListener(event, on)
      resolve()
    }
    const timer = setTimeout(() => {
      el.removeEventListener(event, on)
      reject(new Error(`${event} timeout`))
    }, timeoutMs)
    el.addEventListener(event, on)
  })
}

async function seek(video, t, timeoutMs) {
  if (Math.abs(video.currentTime - t) < 0.01) return
  const done = once(video, 'seeked', timeoutMs)
  video.currentTime = t
  await done
}

function draw(video, maxWidth) {
  const scale = Math.min(1, maxWidth / video.videoWidth)
  const w = Math.max(1, Math.round(video.videoWidth * scale))
  const h = Math.max(1, Math.round(video.videoHeight * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  canvas.getContext('2d').drawImage(video, 0, 0, w, h)
  return canvas
}

/** 抽样算平均亮度，用来识别黑场 */
function luminance(canvas) {
  const sample = document.createElement('canvas')
  sample.width = 16
  sample.height = 9
  const ctx = sample.getContext('2d')
  ctx.drawImage(canvas, 0, 0, 16, 9)
  const px = ctx.getImageData(0, 0, 16, 9).data
  let sum = 0
  for (let i = 0; i < px.length; i += 4) {
    sum += 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2]
  }
  return sum / (px.length / 4)
}

/**
 * @param {File} file 本地视频
 * @returns {Promise<File|null>} JPEG 封面；失败返回 null
 */
export async function createVideoPoster(file, { maxWidth = 720, timeoutMs = 4000 } = {}) {
  if (!file) return null
  const url = URL.createObjectURL(file)
  const video = document.createElement('video')
  video.preload = 'auto'
  video.muted = true
  video.playsInline = true
  video.setAttribute('playsinline', '')
  try {
    const meta = once(video, 'loadedmetadata', timeoutMs)
    video.src = url
    video.load()
    await meta
    const duration = Number.isFinite(video.duration) ? video.duration : 0
    if (!video.videoWidth || !video.videoHeight) return null

    const candidates = [
      Math.min(0.5, duration * 0.1),
      Math.min(1.5, duration * 0.25),
      duration * 0.5,
    ].map((t) => Math.max(0, Math.min(t, Math.max(0, duration - 0.05))))
    const points = [...new Set(candidates)]

    let best = null
    for (const t of points) {
      try {
        await seek(video, t, Math.min(2500, timeoutMs))
        const canvas = draw(video, maxWidth)
        const lum = luminance(canvas)
        if (!best || lum > best.lum) best = { canvas, lum }
        if (lum >= GOOD_LUM) break
      } catch {
        /* 该时间点解码不出来，换下一个 */
      }
    }
    if (!best || best.lum < DARK_LUM) return null

    const blob = await new Promise((resolve) => best.canvas.toBlob(resolve, 'image/jpeg', 0.72))
    if (!blob) return null
    const name = (file.name || 'video').replace(/\.[^.]+$/, '') + '_poster.jpg'
    return new File([blob], name, { type: 'image/jpeg' })
  } catch {
    return null
  } finally {
    try {
      video.pause()
    } catch {
      /* 已卸载 */
    }
    video.removeAttribute('src')
    try {
      video.load()
    } catch {
      /* ignore */
    }
    URL.revokeObjectURL(url)
  }
}

export default createVideoPoster
