<template>
  <!--
    帖子视频统一出口（2026-10-29）。
    抽出来的原因：首页卡片、详情、发帖预览、圈子发帖都要同一套属性，
    其中 poster/preload/playsinline 只要漏一个，移动端就会退回黑块。
    用组件保证四处行为完全一致，而不是每个调用点各写一遍长标签。
  -->
  <video
    :src="resolveVideo(src)"
    :poster="poster ? resolveUrl(poster) : undefined"
    preload="metadata"
    playsinline
    webkit-playsinline
    controls
  ></video>
</template>

<script setup>
import { resolveUrl, resolveVideo } from '@/utils/resolve'

defineProps({
  src: { type: String, default: '' },
  /** 上传时截的封面；旧帖没有时靠 resolveVideo 的 #t 兜底 */
  poster: { type: String, default: '' },
})
</script>
