import { ref } from 'vue'

/**
 * 移动端部分浏览器会把正在播放的 <video> 提升到独立原生层：
 * 弹窗会被视频画面盖住，滚动时视频甚至会盖住吸顶导航。
 * 仅暂停不足以让部分浏览器收回原生层，因此用全局挂起标记让 PostVideo
 * 卸载 <video> 元素、只保留封面；弹窗关闭或重新点击播放时再挂载。
 */
const dialogSuspendCount = ref(0)

export function pauseVideosForDialog() {
  dialogSuspendCount.value += 1
  document.querySelectorAll('video').forEach((v) => {
    if (!v.paused) v.pause()
  })
  if (document.activeElement instanceof HTMLVideoElement) document.activeElement.blur()
}

export function resumeVideosAfterDialog() {
  dialogSuspendCount.value = Math.max(0, dialogSuspendCount.value - 1)
}

export function isVideoSuspended() {
  return dialogSuspendCount.value > 0
}
