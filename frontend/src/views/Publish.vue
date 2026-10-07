<template>
  <div class="publish">
    <div v-if="loading" class="publish-inner">加载中…</div>
    <el-card v-else class="publish-card">
      <el-input
        v-model="content"
        type="textarea"
        :rows="6"
        placeholder="分享你的校园生活…"
        maxlength="5000"
        show-word-limit
      />

      <div class="media-preview">
        <div v-for="(m, i) in media" :key="i" class="media-item">
          <el-button
            class="media-remove"
            circle
            size="small"
            type="danger"
            @click="media.splice(i, 1)"
          >
            <el-icon><Close /></el-icon>
          </el-button>
          <template v-if="m.type === 'img'">
            <el-image :src="resolveUrl(m.url)" fit="cover" class="media-thumb" />
          </template>
          <template v-else>
            <video :src="resolveUrl(m.url)" controls class="media-thumb"></video>
            <span class="media-type">视频</span>
          </template>
        </div>
      </div>

      <!-- 话题(#话题#):逗号或空格分隔多个,如 校园生活,美食 -->
      <el-input
        v-model="topicText"
        placeholder="话题(可选,用逗号分隔,如:校园生活,美食)"
        clearable
        class="topic-input"
      />
      <div v-if="topicTags.length" class="topic-preview">
        <el-tag v-for="t in topicTags" :key="t" size="small" type="primary">
          #{{ t }}
        </el-tag>
      </div>

      <div class="attach-row">
        <el-upload
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="uploadFile"
          accept="image/*"
        >
          <el-button icon="Picture" plain>添加图片</el-button>
        </el-upload>
        <el-upload
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="uploadFile"
          accept="video/*"
        >
          <el-button icon="VideoCamera" plain>添加视频</el-button>
        </el-upload>
      </div>

      <div class="publish-actions">
        <el-button @click="goBack">取消</el-button>
        <el-button type="primary" :loading="posting" @click="submit">{{ isEdit ? '保存' : '发布' }}</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import { upload as uploadApi, createPost, updatePost, detail } from '@/api/feed'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const content = ref('')
const media = ref([])
const posting = ref(false)
const topicText = ref('')
const loading = ref(false)
const mediaUrls = ref([])
const editingId = ref(null)

// 路由名是 postEdit 即编辑模式(同组件复用,route.params.id 变化时全量重载,见铁律⑨)
const isEdit = computed(() => route.name === 'postEdit')

// 新建/编辑都从 ?topic= 预填话题;编辑模式预填该帖的话题名
async function loadPreload() {
  if (isEdit.value) {
    loading.value = true
    try {
      const data = await detail(route.params.id)
      content.value = data.content || ''
      topicText.value = (data.topics || []).join(',')
      let m = data.media
      if (m && typeof m === 'string') { try { m = JSON.parse(m) } catch { m = null } }
      media.value = Array.isArray(m) ? m : []
      mediaUrls.value = media.value.map(x => x.url)
      editingId.value = data.id
    } finally {
      loading.value = false
    }
  } else {
    const t = route.query.topic
    if (t) topicText.value = String(t)
  }
}

// 切 id 或切模式(同组件复用)时全量重载;新建/编辑模式互不残留
watch([() => route.params.id, () => route.name], () => {
  content.value = ''
  media.value = []
  mediaUrls.value = []
  topicText.value = ''
  editingId.value = null
  loadPreload()
}, { immediate: true })

// 把话题输入(逗号/空格分隔)拆成数组,去掉 # 和空
const topicTags = computed(() =>
  topicText.value
    .split(/[,，\s]+/)
    .map((s) => s.trim().replace(/^#+/, '').replace(/#+$/, '').trim())
    .filter((s) => s && s.length <= 64)
    .filter((v, i, a) => a.indexOf(v) === i) // 去重
)

function resolveUrl(url) {
  if (!url) return ''
  return url.startsWith('http') ? url : window.location.origin + url
}

function beforeUpload(file) {
  const isImg = file.type.startsWith('image/')
  const isVideo = file.type.startsWith('video/')
  const maxB = isVideo ? 50 * 1024 * 1024 : 7.5 * 1024 * 1024
  if (!isImg && !isVideo) {
    ElMessage.warning('仅支持图片或视频')
    return false
  }
  if (file.size > maxB) {
    ElMessage.warning(isVideo ? '视频不能超过 50MB' : '图片不能超过 7.5MB')
    return false
  }
  // 只校验、不手动触发:交给 el-upload 的 http-request(uploadFile) 统一走,
  // 避免"before-upload 里同步调异步 upload 且不 await"的竞态
  return true
}

async function uploadFile(option) {
  const { file } = option
  const isVideo = file.type.startsWith('video/')
  const kind = isVideo ? 'video' : 'image'
  try {
    const data = await uploadApi(file, kind)
    media.value.push({ type: kind === 'video' ? 'video' : 'img', url: data.url })
    mediaUrls.value.push(data.url)
  } catch {
    ElMessage.error('上传失败')
  }
}

async function submit() {
  if (!content.value.trim() && media.value.length === 0) {
    ElMessage.warning('先写点什么或传张图')
    return
  }
  posting.value = true
  try {
    if (isEdit.value) {
      const data = await updatePost(editingId.value, {
        content: content.value,
        media: media.value.length ? JSON.stringify(media.value) : null,
        topics: topicTags.value.length ? topicTags.value : null,
      })
      ElMessage.success(data.status === 0 ? '已保存,命中敏感词重回审核' : '已保存')
      router.push(`/post/${data.id}`)
    } else {
      await createPost({
        content: content.value,
        media: media.value.length ? JSON.stringify(media.value) : null,
        topics: topicTags.value.length ? topicTags.value : null,
      })
      ElMessage.success('发布成功')
      router.push('/')
    }
  } catch {
    // request.js 已弹错
  } finally {
    posting.value = false
  }
}

function goBack() {
  router.back()
}
</script>

<style scoped>
.publish {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
}
.media-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 14px 0;
}
.media-item {
  position: relative;
}
.media-thumb {
  width: 120px;
  height: 120px;
  border-radius: 8px;
  object-fit: cover;
  display: block;
}
.media-remove {
  position: absolute;
  top: 4px;
  right: 4px;
  z-index: 1;
}
.media-type {
  position: absolute;
  bottom: 4px;
  right: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
}
.topic-input {
  margin-bottom: 8px;
}
.topic-preview {
  margin-bottom: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.attach-row {
  display: flex;
  gap: 12px;
  margin: 12px 0 4px;
}
.publish-actions {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
