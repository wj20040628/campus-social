<template>
  <!--
    帖子视频统一出口（2026-10-29）。
    抽出来的原因：首页卡片、详情、发帖预览、圈子发帖都要同一套属性，
    其中 poster/preload/playsinline 只要漏一个，移动端就会退回黑块。
    原生 controls 的居中播放键在微信/移动端过大且遮画面，这里改自绘小播放键。
  -->
  <div
    class="pv"
    :style="hidden ? { aspectRatio: ratio ? String(ratio) : '16 / 9' } : null"
    @click="toggle"
  >
    <video
      v-if="!hidden"
      ref="el"
      :src="videoSrc"
      :poster="poster ? resolveUrl(poster) : undefined"
      class="pv__el"
      preload="metadata"
      playsinline
      webkit-playsinline
      disablepictureinpicture
      disableremoteplayback
      @loadedmetadata="onMeta"
      @play="onPlay"
      @pause="playing = false"
      @ended="playing = false"
      @timeupdate="onTime"
    ></video>
    <!--
      挂起时卸载 video、只留封面：部分移动浏览器会把播放中的视频放到
      独立原生层，暂停也收不回来，会导致弹窗和吸顶导航被画面盖住。
    -->
    <img v-else-if="poster" :src="resolveUrl(poster)" class="pv__poster" alt="" />
    <button
      v-if="!playing"
      type="button"
      class="pv__play"
      :aria-label="started ? '继续播放' : '播放视频'"
      @click.stop="toggle"
    ></button>
    <div v-if="started && !hidden" class="pv__bar" aria-hidden="true" @click.stop="seek">
      <i class="pv__fill" :style="{ width: pct + '%' }"></i>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { resolveUrl, resolveVideo } from '@/utils/resolve'
import { isVideoSuspended } from '@/utils/mediaPause'

const props = defineProps({
  src: { type: String, default: '' },
  /** 上传时截的封面；旧帖没有时靠 resolveVideo 的 #t 兜底 */
  poster: { type: String, default: '' },
})

const el = ref(null)
const playing = ref(false)
const started = ref(false)
const pct = ref(0)
const hiddenLocal = ref(false)
const ratio = ref(0)

/*
 * 挂起条件：
 * - 滚动导致本地挂起（视频已离开当前视线，避免浏览器把它提成浮层盖上顶栏）
 * - 任意确认框打开且视频已开始过（卸载 video，收回原生层）
 * 未播放过的视频不卸载，避免卡片高度和封面无谓变化。
 */
const hidden = computed(
  () => hiddenLocal.value || (isVideoSuspended() && started.value)
)

function onMeta() {
  const v = el.value
  if (!v || !v.videoWidth || !v.videoHeight) return
  ratio.value = v.videoWidth / v.videoHeight
}

// 有 poster 就从 0 秒开始；只有没 poster 的旧帖才用 #t=1 取一帧非黑画面
const videoSrc = computed(() => (props.poster ? resolveUrl(props.src) : resolveVideo(props.src)))
const timeFallback = computed(() => !props.poster)

let lastPlayAt = 0

function onPlay() {
  const v = el.value
  lastPlayAt = Date.now()
  // #t=1 只用于静止帧；首次播放回到 0，避免每次都跳过开头 1 秒
  if (!started.value && timeFallback.value && v && v.currentTime > 0.05) v.currentTime = 0
  playing.value = true
  started.value = true
}

/**
 * 播放中一滚动就暂停并卸载视频：
 * 部分手机浏览器会在滚动时把播放中的视频提成独立浮层，浮层会盖住顶栏。
 * 刚点播放的 500ms 内忽略滚动，避免播放按钮聚焦引起的轻微滚动误停。
 */
function onScroll() {
  if (Date.now() - lastPlayAt < 500) return
  const v = el.value
  if (v && !v.paused) {
    v.pause()
    hiddenLocal.value = true
  }
}

onMounted(() => window.addEventListener('scroll', onScroll, { passive: true }))

/*
 * 首页被 KeepAlive 缓存:离开首页时组件不会卸载,播放中的视频会留在缓存树里
 * 继续出声,部分浏览器还会保留原生层。这里主动暂停并卸载,回来时显示封面待播。
 */
onDeactivated(() => {
  const v = el.value
  if (v && !v.paused) v.pause()
  if (started.value) hiddenLocal.value = true
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  const v = el.value
  if (v && !v.paused) v.pause()
})

function onTime() {
  const v = el.value
  if (!v || !Number.isFinite(v.duration) || v.duration <= 0) return
  pct.value = Math.min(100, Math.max(0, (v.currentTime / v.duration) * 100))
}

async function toggle() {
  if (hiddenLocal.value) {
    hiddenLocal.value = false
    await nextTick()
  }
  const v = el.value
  if (!v) return
  lastPlayAt = Date.now()
  if (v.paused || v.ended) {
    try {
      await v.play()
    } catch {
      /* 浏览器拒绝播放时保持暂停 */
    }
  } else {
    v.pause()
  }
}

function seek(e) {
  const v = el.value
  if (!v || !Number.isFinite(v.duration) || v.duration <= 0) return
  const rect = e.currentTarget.getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
  v.currentTime = ratio * v.duration
  pct.value = ratio * 100
}
</script>

<style scoped>
.pv {
  position: relative;
  display: block;
  overflow: hidden;
  background: #000;
  line-height: 0;
}

.pv__el {
  display: block;
  width: 100%;
  height: 100%;
  max-height: inherit;
  object-fit: cover;
}

/* 挂起时用封面占位，保持卡片原有高度 */
.pv__poster {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 尺寸对齐分享卡片：直径约宽度的 12%，上限 64px */
.pv__play {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: clamp(28px, 12%, 64px);
  aspect-ratio: 1;
  padding: 0;
  border: 0;
  border-radius: var(--ys-radius-full);
  background: var(--ys-overlay);
  display: grid;
  place-items: center;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}

.pv__play::after {
  content: '';
  width: 34%;
  height: 42%;
  margin-left: 7%;
  background: #fff;
  clip-path: polygon(0 0, 100% 50%, 0 100%);
}

.pv__play:focus-visible {
  outline: 2px solid #fff;
  outline-offset: 2px;
}

.pv__bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 14px;
  display: flex;
  align-items: flex-end;
  cursor: pointer;
}

.pv__bar::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  background: rgba(255, 255, 255, 0.35);
}

.pv__fill {
  position: relative;
  height: 3px;
  background: #fff;
}
</style>
