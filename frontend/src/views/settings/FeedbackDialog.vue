<template>
  <!--
    意见反馈弹层(2026-10-18)。

    ## 为什么是 Dialog 而不是 ActionSheet
    ActionSheet 的五条错误用法里第一条就是「不承载多步表单」,而这里要选分类 +
    写描述 + 传附件,已经是完整表单了。

    ## 为什么敢复用发帖的上传
    useMediaUpload 的格子状态机(上传中/失败可重传/HEVC 预检/上限 9 个)与反馈
    需求完全一致 —— 复用不是为了省代码,而是因为「上传失败在哪一格」这套交互
    已经过用户验证,再写一份必然在细节上退步。

    ## 分类为什么用整块按钮而不是 el-select
    五个分类 + 每类一句提示,el-select 收起来只有一行字,用户得点开才知道
    「使用问题」和「内容问题」有什么区别。五个一屏放得下,直接点最省事。
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

      <!-- 附件:与发帖同一个格子组件。截图是最快的 bug 报告 -->
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

    <template #footer>
      <el-button @click="open_ = false">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="!canSubmit"
        @click="submit"
      >
        提交
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 提交口径刻意做「宁可不发也不发半条」:
 * - 有格子在上传中 → 禁用提交(附件半路上传,用户会以为截图没传上去)
 * - 有格子失败 → 禁用提交(可以点重传,而不是让用户以为发出去了)
 * - 没写描述 → 禁用
 *
 * 回执提示是「已收到,我们会看的」而不是「已采纳」:后者会误导用户以为产品已经改了。
 * 失败不额外弹提示:request.js 的全局拦截器已经弹过一条,再弹就成了两条矛盾信息。
 */
import { computed, reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { FEEDBACK_CATEGORIES, submitFeedback } from '@/api/feedback'
import { useMediaUpload } from '@/composables/useMediaUpload'
import { useIsMobile } from '@/composables/useBreakpoint'
import MediaGrid from '@/components/post/MediaGrid.vue'

const emit = defineEmits(['submitted'])

const isMobile = useIsMobile()
const open_ = ref(false)
const saving = ref(false)
const form = reactive({ category: 1, content: '' })

const {
  cells, doneCount, failedCount, readyMedia,
  beforeUpload, uploadFile, retry, remove, reset, videoEnabled,
} = useMediaUpload()

/** 上传中的格子也算「还没传完」,所以单独数 */
const uploadingCount = computed(() => cells.value.filter((c) => c.state === 'uploading').length)

const canSubmit = computed(() =>
  !!form.category
  && form.content.trim().length > 0
  && !saving.value
  && failedCount.value === 0
  && uploadingCount.value === 0
)

function open() {
  open_.value = true
}

/** 关闭后清空:反馈是一次性诉求,留着上一次的描述会让人以为没提交干净 */
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
    open_.value = false
    emit('submitted')
    ElMessage.success('已收到，我们会看的')
  } catch {
    // 全局拦截器已提示,这里不重复弹
  } finally {
    saving.value = false
  }
}

defineExpose({ open })
</script>

<style scoped src="./FeedbackDialog.css"></style>
