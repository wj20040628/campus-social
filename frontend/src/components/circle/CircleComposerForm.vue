<template>
  <!--
    圈子发帖表单本体（2026-10-21 从 CircleComposer.vue 抽出）。

    抽出来的理由：发帖现在有两种外壳（手机底部抽屉 / 桌面居中弹窗），
    而**表单本身完全相同** —— textarea、媒体预览、上传、字数、发布按钮、错误文案。
    写两遍意味着这 80 行 UI 要维护两份，改一处必然漏另一处。
    本组件只管表单，外壳（何时显示、以什么形态显示）在 CircleComposer.vue。
  -->
  <div class="cff">
    <p v-if="error" class="cff__err" role="alert">{{ error }}</p>

    <el-input
      ref="textareaRef"
      v-model="content"
      class="cff__box"
      type="textarea"
      :rows="5"
      :maxlength="5000"
      show-word-limit
      :placeholder="`在「${circleName}」分享点什么…`"
      aria-label="帖子内容"
    />

    <ul v-if="media.length" class="cff__media">
      <li v-for="(m, i) in media" :key="m.url" class="cff__media-item">
        <el-image v-if="m.type === 'img'" :src="resolveThumb(m.url)" fit="cover" class="cff__thumb" />
        <video v-else :src="resolveUrl(m.url)" controls class="cff__thumb" />
        <button
          type="button"
          class="cff__media-del"
          :aria-label="`移除第 ${i + 1} 个附件`"
          @click="media.splice(i, 1)"
        >
          <el-icon :size="14"><Close /></el-icon>
        </button>
      </li>
    </ul>

    <div class="cff__ops">
      <el-upload
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="uploadFile"
        :accept="videoEnabled ? 'image/*,video/*' : 'image/*'"
      >
        <button type="button" class="cff__op-btn" :disabled="uploading">
          <el-icon :size="18"><Picture /></el-icon>
          <span>{{ videoEnabled ? '图片或视频' : '图片' }}</span>
        </button>
      </el-upload>

      <button
        type="button"
        class="cff__submit"
        :disabled="!canSubmit || posting"
        @click="$emit('submit')"
      >
        {{ posting ? '发布中' : '发布到圈子' }}
      </button>
    </div>

    <!--
      视频格式提示：只在真要传视频时才需要，所以做成一个「?」按需展开。
      常驻两行 10.5px 灰字对 99% 的用户是噪音 ——
      而这段限制的最佳出现时机是**上传失败那一刻**，那里已有针对性提示
      （见 beforeUpload 的 H.265 与体积拦截）。
    -->
    <p v-if="videoEnabled" class="cff__hint">
      <button type="button" class="cff__hint-btn" :aria-expanded="showHint" @click="showHint = !showHint">
        <span class="cff__hint-q">?</span>
        视频格式说明
      </button>
      <span v-if="showHint" class="cff__hint-text">
        支持 {{ VIDEO_SUPPORTED_FORMATS }}，单条 ≤ {{ Math.round(limits.maxVideoBytes / 1024 / 1024) }}MB；{{ VIDEO_UNSUPPORTED_NOTE }}
      </span>
    </p>
  </div>
</template>

<script setup>
/**
 * 圈子发帖表单本体。
 *
 * textarea 固定 5 行（浮层里空间足够），不再需要原来「聚焦展开」的 max-height 过渡 ——
 * 那个技巧是为了在常驻状态下省高度，现在表单只在浮层里出现，省高度没有意义了。
 */
import { computed, ref } from 'vue'
import { Close, Picture } from '@element-plus/icons-vue'
import { uploadToOss } from '@/composables/useOssUpload'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import { ElMessage } from '@/utils/feedback'
import { ensureUploadConfig, limits } from '@/composables/useUploadConfig'
import { isHevcFile, VIDEO_SUPPORTED_FORMATS, VIDEO_UNSUPPORTED_NOTE } from '@/utils/videoCodec'

const props = defineProps({
  circleName: { type: String, default: '' },
  content: { type: String, default: '' },
  media: { type: Array, default: () => [] },
  posting: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['submit', 'update:content', 'update:media'])

const textareaRef = ref(null)
const showHint = ref(false)

const content = computed({
  get: () => props.content,
  set: (v) => emit('update:content', v),
})

const media = computed({
  get: () => props.media,
  set: (v) => emit('update:media', v),
})

const canSubmit = computed(() => !!(content.value.trim() || media.value.length))

/** 外壳在抽屉动画结束后调用它 */
function focus() {
  textareaRef.value?.focus()
}

defineExpose({ focus })

// 进页面就探一次：视频开关 + 单文件上限（与 Publish 页共享同一份缓存）
ensureUploadConfig()
const videoEnabled = computed(() => limits.value.videoEnabled)
const MAX_IMAGE = computed(() => limits.value.maxImageBytes)
const MAX_VIDEO = computed(() => limits.value.maxVideoBytes)

async function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isVideo = file.type.startsWith('video/')
  if (isVideo && !videoEnabled.value) {
    ElMessage.warning('视频功能已暂停，请改用图片')
    return false
  }
  if (!isImage && !isVideo) {
    ElMessage.warning(videoEnabled.value ? '只支持图片或视频' : '只支持图片')
    return false
  }
  // HEVC 预检：浏览器普遍不支持 H.265，提前拦比传完失败友好（与后端 VideoCodecUtil 同判据）
  if (isVideo && (await isHevcFile(file))) {
    ElMessage.warning('视频编码不支持（H.265/HEVC），请用 H.264 编码的 mp4 重新上传')
    return false
  }
  const max = isVideo ? MAX_VIDEO.value : MAX_IMAGE.value
  if (file.size > max) {
    ElMessage.warning(
      isVideo
        ? `视频不能超过 ${Math.round(MAX_VIDEO.value / 1024 / 1024)}MB`
        : `图片不能超过 ${Math.round(MAX_IMAGE.value / 1024 / 1024)}MB`,
    )
    return false
  }
  return true
}

async function uploadFile({ file }) {
  const isVideo = file.type.startsWith('video/')
  try {
    const data = await uploadToOss(file, isVideo ? 'video' : 'image')
    if (data?.url) media.value = [...media.value, { type: isVideo ? 'video' : 'img', url: data.url }]
  } catch (e) {
    // 上传失败：附件没加上，但已打的字还在。
    // 不提示等于让用户以为图传上去了，发完帖子才发现少一张。
    const msg = e?.response?.data?.message || e?.businessMessage || e?.message
    ElMessage.error(msg || '附件没传上去，再试一次')
  }
}
</script>

<style scoped src="./CircleComposerForm.css"></style>
