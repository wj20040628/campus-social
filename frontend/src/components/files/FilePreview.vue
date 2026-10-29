<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fp"
      role="dialog"
      aria-modal="true"
      :aria-label="`预览：${file?.title || ''}`"
      tabindex="-1"
      @keydown.esc="close"
    >
      <header class="fp__bar">
        <button type="button" class="fp__close" aria-label="关闭预览" @click="close">
          &#10005;
        </button>
        <div class="fp__headtext">
          <span class="fp__title">{{ file?.title || '文件预览' }}</span>
          <span class="fp__meta">
            {{ fileExtText(file?.ext) }}<template v-if="file?.fileName"> · {{ file.fileName }}</template>
          </span>
        </div>
        <button type="button" class="fp__download" @click="$emit('download')">下载</button>
      </header>

      <div class="fp__body">
        <p v-if="loading" class="fp__state">正在加载…</p>
        <p v-else-if="error" class="fp__state fp__state--err">{{ error }}</p>
        <template v-else-if="isPdf">
          <div ref="pagesEl" class="fp__pages" />
          <p v-if="pageCount" class="fp__hint">
            <template v-if="pageCount > renderedPages">
              仅预览前 {{ renderedPages }} 页（共 {{ pageCount }} 页），完整内容请下载查看
            </template>
            <template v-else>共 {{ pageCount }} 页</template>
          </p>
        </template>
        <template v-else>
          <pre class="fp__text">{{ text }}</pre>
          <p v-if="truncated" class="fp__hint">内容较长，仅显示前 200KB，完整内容请下载查看</p>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 文件在线预览（2026-10-27）。
 *
 * PDF 用 pdfjs-dist 画到 canvas，而不是 iframe：Android Chrome 没有
 * 内置 PDF 查看器，iframe 会直接空白/触发下载，手机端等于没预览。
 * 只渲染前 8 页，避免几十页的 PDF 把低端机内存打满；剩余内容引导下载。
 *
 * TXT/MD 直接渲染文本。Word/PPT/Excel 不做伪预览 —— 浏览器打不开，
 * 转格式需要 LibreOffice/POI，当前 2C2G 的 ECS 不适合为此加转换链路。
 *
 * 取字节统一走带 token 的 fetch：已过审文件游客也能预览，上传者预览
 * 自己的待审文件则靠 Authorization 头通过后端的 owner 判定。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as fileApi from '@/api/files'
import { fileExtText, isPreviewable } from '@/constants/fileCategories'

const MAX_PDF_PAGES = 8

const props = defineProps({
  open: { type: Boolean, default: false },
  file: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'download'])

const loading = ref(false)
const error = ref('')
const text = ref('')
const truncated = ref(false)
const pagesEl = ref(null)
const pageCount = ref(0)
const renderedPages = ref(0)

const isPdf = computed(() => String(props.file?.ext || '').toLowerCase() === 'pdf')

let requestSeq = 0
let lastOverflow = ''
let pdfDoc = null
let pdfjsModule = null

function clearPages() {
  if (pagesEl.value) pagesEl.value.innerHTML = ''
  pageCount.value = 0
  renderedPages.value = 0
}

async function destroyPdf() {
  if (pdfDoc) {
    try { await pdfDoc.destroy() } catch { /* ignore */ }
    pdfDoc = null
  }
}

function close() {
  emit('update:open', false)
}

async function loadPdf(blob, seq) {
  const pdfjs = pdfjsModule || (pdfjsModule = await import('pdfjs-dist'))
  const workerUrl = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = workerUrl
  await destroyPdf()
  pdfDoc = await pdfjs.getDocument({ data: new Uint8Array(await blob.arrayBuffer()) }).promise
  if (seq !== requestSeq) {
    await destroyPdf()
    return
  }
  pageCount.value = pdfDoc.numPages
  if (!pdfDoc.numPages) {
    throw new Error('这份 PDF 没有可显示的页面')
  }
  /*
   * 必须先结束 loading 再 nextTick：.fp__pages 在 v-else-if 链里，
   * loading 为 true 时它根本没挂载，pagesEl 会是 null，一页都画不出来。
   */
  loading.value = false
  await nextTick()
  const host = pagesEl.value
  if (!host) return
  const avail = Math.min(820, host.clientWidth || 360) - 24
  const total = Math.min(pdfDoc.numPages, MAX_PDF_PAGES)
  for (let i = 1; i <= total; i++) {
    if (seq !== requestSeq) return
    const page = await pdfDoc.getPage(i)
    const base = page.getViewport({ scale: 1 })
    const scale = Math.min(2.2, Math.max(0.5, avail / base.width))
    const viewport = page.getViewport({ scale })
    const canvas = document.createElement('canvas')
    canvas.className = 'fp__page'
    canvas.width = Math.floor(viewport.width)
    canvas.height = Math.floor(viewport.height)
    canvas.style.width = `${Math.floor(viewport.width)}px`
    canvas.style.height = `${Math.floor(viewport.height)}px`
    host.appendChild(canvas)
    await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise
    if (seq !== requestSeq) return
    renderedPages.value = i
  }
}

async function load() {
  const seq = ++requestSeq
  clearPages()
  text.value = ''
  truncated.value = false
  error.value = ''
  await destroyPdf()
  const file = props.file
  if (!file?.id) return
  if (!isPreviewable(file.ext)) {
    error.value = '该格式暂不支持在线预览，请下载后用本地软件打开'
    return
  }
  loading.value = true
  try {
    const blob = await fileApi.previewBlob(file.id)
    if (seq !== requestSeq) return
    if (isPdf.value) {
      await loadPdf(blob, seq)
    } else {
      const raw = await blob.text()
      // 3MB 纯文本全塞进 DOM 会让低端机卡住；预览展示前 20 万字符，其余下载看
      const LIMIT = 200_000
      truncated.value = raw.length > LIMIT
      text.value = truncated.value ? raw.slice(0, LIMIT) : raw
    }
  } catch (e) {
    if (seq === requestSeq) error.value = e?.message || '预览加载失败，请下载查看'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

watch(
  () => [props.open, props.file?.id],
  ([open]) => {
    if (open) {
      lastOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      load()
    } else {
      requestSeq++
      loading.value = false
      clearPages()
      destroyPdf()
      document.body.style.overflow = lastOverflow
    }
  }
)

onBeforeUnmount(() => {
  requestSeq++
  destroyPdf()
  document.body.style.overflow = lastOverflow
})
</script>

<style scoped src="./FilePreview.css"></style>
