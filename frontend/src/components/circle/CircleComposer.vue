<template>
  <!--
    圈子内嵌发帖框(CircleDetail.vue.md §1 行 4)
    **仅成员可见**(§3 权限不足:非成员不渲染,改显示一行说明 + 「加入圈子」按钮,
    禁止「点了才说你没权限」)。

    发帖失败时**内容保留不清空**(§3 错误态):用户写了 200 字点发布失败后被清空,
   是最伤的交互。
  -->
  <div class="cf">
    <p v-if="error" class="cf__err" role="alert">{{ error }}</p>

    <el-input
      ref="textareaRef"
      v-model="content"
      class="cf__box"
      type="textarea"
      :rows="3"
      :maxlength="5000"
      show-word-limit
      :placeholder="`在「${circleName}」分享点什么…`"
      aria-label="帖子内容"
      @focus="expanded = true"
      @blur="expanded = false"
    />

    <ul v-if="media.length" class="cf__media">
      <li v-for="(m, i) in media" :key="m.url" class="cf__media-item">
        <el-image v-if="m.type === 'img'" :src="resolveThumb(m.url)" fit="cover" class="cf__thumb" />
        <video v-else :src="resolveUrl(m.url)" controls class="cf__thumb" />
        <button
          type="button"
          class="cf__media-del"
          :aria-label="`移除第 ${i + 1} 个附件`"
          @click="media.splice(i, 1)"
        >
          <el-icon :size="14"><Close /></el-icon>
        </button>
      </li>
    </ul>

    <div class="cf__ops">
      <el-upload
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="uploadFile"
        :accept="videoEnabled ? 'image/*,video/*' : 'image/*'"
      >
        <button type="button" class="cf__op-btn" :disabled="uploading">
          <el-icon :size="20"><Picture /></el-icon>
          <span class="cf__op-text">{{ videoEnabled ? '添加图片或视频' : '添加图片' }}</span>
        </button>
      </el-upload>

      <button
        type="button"
        class="cf__submit"
        :disabled="!canSubmit || posting"
        @click="$emit('submit')"
      >
        {{ posting ? '发布中' : '发布到圈子' }}
      </button>
    </div>

    <!-- 视频格式提示:仅在视频入口开启时显示,与后端白名单/HEVC 拦截对齐 -->
    <p v-if="videoEnabled" class="cf__hint">
      视频支持 {{ VIDEO_SUPPORTED_FORMATS }}，单条 ≤ {{ Math.round(limits.maxVideoBytes / 1024 / 1024) }}MB；{{ VIDEO_UNSUPPORTED_NOTE }}
    </p>
  </div>
</template>

<script setup>
/**
 * 内嵌发帖框
 *
 * 聚焦时 textarea 从 3 行展开到 6 行 —— 用 max-height 过渡,
 * **不动 height 的 px 值**(§4),否则光标会跳。
 */
import { ref, computed } from 'vue'
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

const expanded = ref(false)
const textareaRef = ref(null)

const content = computed({
  get: () => props.content,
  set: (v) => emit('update:content', v),
})

const media = computed({
  get: () => props.media,
  set: (v) => emit('update:media', v),
})

const canSubmit = computed(() => !!(content.value.trim() || media.value.length))

/** 空态的「发第一条」按钮要能聚焦到这里 */
function focus() {
  textareaRef.value?.focus()
}

defineExpose({ focus })

// 进圈子详情页就探一次:视频开关 + 单文件上限(与 Publish 页共享同一份缓存)
ensureUploadConfig()
const videoEnabled = computed(() => limits.value.videoEnabled)
const MAX_IMAGE = computed(() => limits.value.maxImageBytes)
const MAX_VIDEO = computed(() => limits.value.maxVideoBytes)

async function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isVideo = file.type.startsWith('video/')
  if (isVideo && !videoEnabled.value) {
    ElMessage.warning('视频功能已暂停,请改用图片')
    return false
  }
  if (!isImage && !isVideo) {
    ElMessage.warning(videoEnabled.value ? '只支持图片或视频' : '只支持图片')
    return false
  }
  // HEVC 预检:浏览器普遍不支持 H.265,提前拦比传完失败友好(与后端 VideoCodecUtil 同判据)
  if (isVideo && (await isHevcFile(file))) {
    ElMessage.warning('视频编码不支持(H.265/HEVC)，请用 H.264 编码的 mp4 重新上传')
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
    // 上传失败:附件没加上,但已打的字还在。
    // 不提示等于让用户以为图传上去了,发完帖子才发现少一张。
    const msg = e?.response?.data?.message || e?.businessMessage || e?.message
    ElMessage.error(msg || '附件没传上去,再试一次')
  }
}
</script>

<style scoped>
/*
 * 卡片:1px 边框 + --ys-surface。**不叠阴影** ——
 * §8.1 禁「幽灵卡片」(1px 边框与 blur≥16px 的阴影同时出现)。
 */
.cf {
  padding: var(--ys-space-4);
  background: var(--ys-surface);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-xl);
}

.cf__err {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

.cf__box {
  width: 100%;
}

/*
 * 聚焦展开:max-height 过渡而不是 rows/height。
 * EP textarea 的内部高度由 rows 计算,这里改外层 max-height 更稳。
 */
.cf__box :deep(.el-textarea__inner) {
  max-height: 96px;
  transition: max-height var(--ys-motion-base) var(--ys-ease-out);
  font-family: var(--ys-font-body);
}

.cf__media {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: var(--ys-space-3) 0 0;
  padding: 0;
  list-style: none;
}

.cf__media-item {
  position: relative;
}

.cf__thumb {
  width: 80px;
  height: 80px;
  border-radius: var(--ys-radius-lg);
  object-fit: cover;
  display: block;
}

.cf__media-del {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: var(--ys-radius-full);
  background: var(--ys-fg);
  color: var(--ys-surface);
  cursor: pointer;
}

.cf__ops {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-3);
  margin-top: var(--ys-space-3);
}

.cf__op-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  border-radius: var(--ys-radius-lg);
}

.cf__op-btn:disabled {
  color: var(--ys-meta);
  cursor: not-allowed;
}

.cf__submit {
  min-height: var(--ys-touch-min);
  min-width: 96px;
  padding: 0 var(--ys-space-4);
  border-radius: var(--ys-radius-lg);
  border: 1px solid var(--ys-accent);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.cf__submit:disabled {
  background: var(--ys-surface-2);
  border-color: var(--ys-border);
  color: var(--ys-meta);
  cursor: not-allowed;
}

.cf__submit:not(:disabled):active {
  opacity: 0.7;
}

/*
  视频格式提示:位于操作栏下方,占满整行。
  配色用 --ys-meta(次要灰),不抢主操作。
*/
.cf__hint {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-meta);
}
</style>
