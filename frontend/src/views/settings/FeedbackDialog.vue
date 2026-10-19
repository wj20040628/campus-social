<template>
  <!--
    意见反馈弹层。两个标签:提交反馈 / 我的反馈(记录收进本弹层,不另开入口;
    切到「我的反馈」才懒加载)。提交口径不变:上传中/失败/空描述禁用提交,
    回执「已收到,我们会看的」,失败不额外弹(全局拦截器已弹过)。
  -->
  <el-dialog
    v-model="open_"
    title="意见反馈"
    :width="isMobile ? '94%' : '560px'"
    class="fbk__dialog"
    append-to-body
    @closed="resetForm"
  >
    <div class="fbk">
      <div class="fbk__tabs" role="tablist" aria-label="反馈区切换">
        <button
          type="button"
          class="fbk__tab"
          :class="{ 'fbk__tab--on': tab === 'submit' }"
          :aria-selected="tab === 'submit'"
          role="tab"
          @click="tab = 'submit'"
        >提交反馈</button>
        <button
          type="button"
          class="fbk__tab"
          :class="{ 'fbk__tab--on': tab === 'mine' }"
          :aria-selected="tab === 'mine'"
          role="tab"
          @click="switchMine"
        >我的反馈</button>
      </div>

      <div v-show="tab === 'submit'" class="fbk__pane">
        <fieldset class="fbk__cats">
          <legend class="fbk__legend">遇到的是哪一类</legend>
          <div class="fbk__catgrid">
            <button
              v-for="c in FEEDBACK_CATEGORIES"
              :key="c.value"
              type="button"
              class="fbk__cat"
              :class="{ 'fbk__cat--on': form.category === c.value }"
              :aria-pressed="form.category === c.value"
              @click="form.category = c.value"
            >
              <span class="fbk__catname">{{ c.label }}</span>
              <span class="fbk__cathint">{{ c.hint }}</span>
            </button>
          </div>
        </fieldset>

        <label class="fbk__label" for="fbk-content">详细说说</label>
        <el-input
          id="fbk-content"
          v-model="form.content"
          type="textarea"
          :rows="4"
          maxlength="1000"
          show-word-limit
          resize="none"
          placeholder="在哪个页面、点了哪里、当时看到什么。描述越具体，改起来越快"
        />

        <div class="fbk__attach">
          <span class="fbk__label fbk__label--inline">截图或录屏（可选）</span>
          <el-upload
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="uploadFile"
            accept="image/*"
          >
            <button type="button" class="fbk__add" aria-label="添加截图">
              <el-icon :size="18"><Picture /></el-icon>
            </button>
          </el-upload>
          <el-upload
            v-if="videoEnabled"
            :show-file-list="false"
            :before-upload="beforeUpload"
            :http-request="uploadFile"
            accept="video/*"
          >
            <button type="button" class="fbk__add" aria-label="添加录屏">
              <el-icon :size="18"><VideoCamera /></el-icon>
            </button>
          </el-upload>
        </div>
        <MediaGrid :cells="cells" @remove="removeCell" @retry="retryCell" />

        <p v-if="doneCount > 0" class="fbk__mediatip">
          已附 {{ doneCount }} 个<template v-if="failedCount">，{{ failedCount }} 个上传失败</template>
        </p>
      </div>

      <div v-show="tab === 'mine'" class="fbk__pane fbk__mine">
        <div v-if="mineLoading" class="fbk__empty">加载中…</div>
        <div v-else-if="mineError" class="fbk__empty">
          没拉出来，<button type="button" class="fbk__retry" @click="loadMine(true)">重试</button>
        </div>
        <div v-else-if="!mineList.length" class="fbk__empty">还没有提交过反馈</div>
        <template v-else>
          <ul class="fbk__list">
            <li v-for="f in mineList" :key="f.id" class="fcard">
              <div class="fcard__chips">
                <span class="chip" :class="`chip--${STATUS_TONE[f.status ?? 0]}`">{{ FEEDBACK_STATUS_TEXT[f.status ?? 0] }}</span>
                <span class="chip chip--cat">{{ CATEGORY_TEXT[f.category] || '其他' }}</span>
              </div>
              <p class="fcard__body">{{ f.content }}</p>
              <ul v-if="f.media.length" class="fcard__media">
                <li v-for="(m, i) in f.media" :key="i" class="fcard__cell">
                  <el-image
                    v-if="m.type === 'img'"
                    :src="resolveThumb(m.url)"
                    fit="cover"
                    class="fcard__thumb"
                    :preview-src-list="imgUrls(f)"
                    :initial-index="i"
                    preview-teleported
                    :alt="`反馈截图 ${i + 1}`"
                  />
                  <video v-else :src="resolveUrl(m.url)" class="fcard__thumb" controls preload="metadata" />
                </li>
              </ul>
              <p v-else-if="f.mediaBroken" class="fcard__warn">附件加载不出来</p>
              <p class="fcard__meta ys-num">{{ timeText(f.createTime) }}</p>
              <div v-if="f.handleNote" class="fcard__reply">
                <span class="fcard__replylabel">管理员回复</span>
                <p class="fcard__replytext">{{ f.handleNote }}</p>
                <p v-if="f.handleTime" class="fcard__replytime ys-num">{{ timeText(f.handleTime) }}</p>
              </div>
            </li>
          </ul>
          <p v-if="mineHasMore" class="fbk__more" @click="loadMine(false)">看更多</p>
        </template>
      </div>
    </div>

    <template #footer>
      <template v-if="tab === 'submit'">
        <el-button @click="open_ = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!canSubmit"
          @click="submit"
        >提交</el-button>
      </template>
      <el-button v-else @click="open_ = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 提交口径「宁可不发也不发半条」:上传中/失败/空描述都禁用提交。
 * 提交成功后不关弹层,直接切到「我的反馈」标签并刷新列表,让用户立刻看到刚提交的这条。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import {
  FEEDBACK_CATEGORIES, FEEDBACK_STATUS_TEXT, CATEGORY_TEXT,
  submitFeedback, myFeedback,
} from '@/api/feedback'
import { useMediaUpload } from '@/composables/useMediaUpload'
import { useIsMobile } from '@/composables/useBreakpoint'
import { resolveThumb, resolveUrl } from '@/utils/resolve'
import MediaGrid from '@/components/post/MediaGrid.vue'

const emit = defineEmits(['submitted'])

const isMobile = useIsMobile()
const open_ = ref(false)
const saving = ref(false)
const tab = ref('submit')
const form = reactive({ category: 1, content: '' })

const {
  cells, doneCount, failedCount, readyMedia,
  beforeUpload, uploadFile, retry, remove, reset, videoEnabled,
} = useMediaUpload()

const uploadingCount = computed(() => cells.value.filter((c) => c.state === 'uploading').length)

const canSubmit = computed(() =>
  !!form.category
  && form.content.trim().length > 0
  && !saving.value
  && failedCount.value === 0
  && uploadingCount.value === 0
)

const mineList = ref([])
const mineLoading = ref(false)
const mineError = ref(false)
const mineCursor = ref(null)
const mineHasMore = ref(false)

const STATUS_TONE = { 0: 'live', 1: 'done', 2: 'off' }

function parseMedia(raw) {
  if (!raw) return { list: [], broken: false }
  try {
    const list = JSON.parse(raw)
    return { list: Array.isArray(list) ? list : [], broken: false }
  } catch {
    return { list: [], broken: true }
  }
}

function normalize(rows) {
  return (rows || []).map((r) => {
    const pm = parseMedia(r.mediaJson)
    return { ...r, media: pm.list, mediaBroken: pm.broken }
  })
}

async function loadMine(reset = false) {
  if (reset) {
    mineLoading.value = true
    mineError.value = false
  }
  try {
    const data = await myFeedback({ cursor: reset ? null : mineCursor.value })
    const rows = normalize(data?.list || [])
    mineList.value = reset ? rows : [...mineList.value, ...rows]
    mineCursor.value = data?.nextCursor ?? null
    mineHasMore.value = !!data?.hasMore
  } catch {
    if (reset) mineError.value = true
  } finally {
    if (reset) mineLoading.value = false
  }
}

function switchMine() {
  tab.value = 'mine'
  if (!mineList.value.length && !mineError.value && !mineLoading.value) loadMine(true)
}

function imgUrls(f) {
  return f.media.filter((m) => m.type !== 'video').map((m) => resolveThumb(m.url))
}

function timeText(t) {
  if (!t) return ''
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function open() {
  open_.value = true
  tab.value = 'submit'
}

function resetForm() {
  form.category = 1
  form.content = ''
  reset()
}

const removeCell = remove
const retryCell = retry

async function submit() {
  if (!canSubmit.value) return
  saving.value = true
  try {
    await submitFeedback({
      category: form.category,
      content: form.content.trim(),
      media: readyMedia.value,
    })
    emit('submitted')
    ElMessage.success('已收到，我们会看的')
    form.category = 1
    form.content = ''
    reset()
    tab.value = 'mine'
    mineList.value = []
    mineCursor.value = null
    mineHasMore.value = false
    await loadMine(true)
  } catch {
    // 全局拦截器已提示,这里不重复弹
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped src="./FeedbackDialog.css"></style>
