<template>
  <div class="cdetail">
    <div class="cdetail-inner">
      <el-page-header @back="router.back()" :title="circle?.name || '圈子'" />

      <div v-if="circle" class="c-banner">
        <div class="c-title-row">
          <div class="c-name">{{ circle.name }}</div>
          <div class="c-join">
            <el-button
              v-if="!circle.joined"
              type="primary"
              :icon="Plus"
              round
              @click="join"
            >
              加入圈子
            </el-button>
            <el-tag v-else type="success">已加入</el-tag>
          </div>
        </div>
        <div class="c-meta">
          <el-avatar :size="32" :src="resolveUrl(circle.owner?.avatarUrl)" />
          <span class="c-owner">{{ circle.owner ? displayName(circle.owner) : '' }}</span>
          <span class="c-count">{{ circle.memberCount }} 成员</span>
        </div>
      </div>

      <el-tabs v-model="tab" class="c-tabs">
        <el-tab-pane label="帖子" name="posts" />
        <el-tab-pane label="活动" name="activities" />
      </el-tabs>

      <!-- 帖子 tab -->
      <div v-if="tab === 'posts'" class="tab-body">
        <!-- 内嵌发帖框:仅圈内成员可见 -->
        <el-card v-if="canCreate" class="compose-card" shadow="never">
          <div class="compose-text">
            <el-input
              v-model="postContent"
              type="textarea"
              :rows="3"
              :maxlength="5000"
              show-word-limit
              :placeholder="`在「${circle?.name}」分享点什么…`"
            />
          </div>
          <div class="compose-media">
            <div v-for="(m, i) in postMedia" :key="i" class="media-item">
              <el-image v-if="m.type === 'img'" :src="resolveUrl(m.url)" fit="cover" class="media-thumb" />
              <video v-else :src="resolveUrl(m.url)" controls class="media-thumb"></video>
              <el-button text type="danger" @click="postMedia.splice(i, 1)">移除</el-button>
            </div>
          </div>
          <div class="compose-actions">
            <el-upload
              :show-file-list="false"
              :before-upload="beforeUpload"
              :http-request="uploadFile"
              accept="image/*"
            >
              <el-button plain icon="Picture">添加图片</el-button>
            </el-upload>
            <el-button type="primary" :loading="posting" :disabled="!postContent.trim() && !postMedia.length" @click="submitPost">
              发布到圈子
            </el-button>
          </div>
        </el-card>

        <PostCard v-for="p in posts" :key="p.id" :post="p" @click="router.push(`/post/${p.id}`)" />
        <el-empty v-if="posts.length === 0" description="圈内还没有动态,来发第一条吧" />
      </div>

      <!-- 活动 tab -->
      <div v-else class="tab-body">
        <div class="act-toolbar">
          <el-button v-if="canCreate" type="primary" round @click="showActForm = true">
            + 发布活动
          </el-button>
          <el-text v-else-if="circle" type="info">加入圈子后即可发布活动</el-text>
        </div>

        <el-card
          v-for="a in activities"
          :key="a.id"
          class="act-card"
          @click="router.push(`/activity/${a.id}`)"
        >
          <div class="act-head">
            <span class="act-title">{{ a.title }}</span>
            <el-tag :type="actStatusTag(a.status)" size="small">{{ actStatusText(a.status) }}</el-tag>
          </div>
          <div class="act-time">
            {{ a.startTime && a.endTime ? dayjs(a.startTime).format('MM-DD HH:mm') + ' ~ ' + dayjs(a.endTime).format('MM-DD HH:mm') : '' }}
          </div>
          <div class="act-foot">
            <span>名额 {{ a.capacity === 0 ? '不限' : a.currentCount + ' / ' + a.capacity }}</span>
          </div>
        </el-card>
        <el-empty v-if="activities.length === 0" description="圈内还没有活动" />
      </div>
    </div>

    <!-- 发布活动弹窗 -->
    <el-dialog v-model="showActForm" title="发布活动" width="480px">
      <el-form :model="actForm" label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="actForm.title" placeholder="活动标题" maxlength="128" />
        </el-form-item>
        <el-form-item label="名额">
          <el-input-number v-model="actForm.capacity" :min="0" :max="99999" />
          <span class="cap-hint">0 = 不限</span>
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="actForm.startTime" type="datetime" placeholder="选择开始时间" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="actForm.endTime" type="datetime" placeholder="选择结束时间" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showActForm = false">取消</el-button>
        <el-button type="primary" :loading="creatingAct" @click="submitActivity">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { ElMessage } from 'element-plus'
import {
  detail as detailApi,
  join as joinApi,
  circlePosts,
  activitiesByCircle,
  createActivity,
} from '@/api/circle'
import { createPost, upload as uploadApi } from '@/api/feed'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const circle = ref(null)
const posts = ref([])
const activities = ref([])
const tab = ref('posts')

const myId = computed(() => userStore.userInfo?.id)
// 只有圈内成员(或创建者)能发
const canCreate = computed(() => circle.value?.joined)

// 发帖框
const postContent = ref('')
const postMedia = ref([])
const posting = ref(false)

// 发活动弹窗
const showActForm = ref(false)
const creatingAct = ref(false)
const actForm = ref({ title: '', capacity: 0, startTime: null, endTime: null })

async function load() {
  const id = route.params.id
  circle.value = await detailApi(id)
  if (tab.value === 'posts') {
    const data = await circlePosts(id, null, 50)
    posts.value = data.list.map((p) => ({ ...p, createTime: dayjs(p.createTime).format('MM-DD HH:mm') }))
  } else {
    const data = await activitiesByCircle(id, null, 50)
    activities.value = data.list
  }
}

async function join() {
  await joinApi(route.params.id)
  circle.value.joined = true
  ElMessage.success('已加入圈子')
}

// 发帖
function beforeUpload(file) {
  const isImg = file.type.startsWith('image/')
  const isVideo = file.type.startsWith('video/')
  if (!isImg && !isVideo) {
    ElMessage.warning('仅支持图片或视频')
    return false
  }
  const maxB = isVideo ? 50 * 1024 * 1024 : 7.5 * 1024 * 1024
  if (file.size > maxB) {
    ElMessage.warning(isVideo ? '视频不能超过 50MB' : '图片不能超过 7.5MB')
    return false
  }
  uploadFile({ file, kind: isVideo ? 'video' : 'image' })
  return false
}

async function uploadFile({ file, kind }) {
  try {
    const data = await uploadApi(file, kind)
    postMedia.value.push({ type: kind === 'video' ? 'video' : 'img', url: data.url })
  } catch {
    ElMessage.error('上传失败')
  }
}

async function submitPost() {
  if (!postContent.value.trim() && postMedia.value.length === 0) {
    ElMessage.warning('先写点什么或传张图')
    return
  }
  posting.value = true
  try {
    await createPost({
      content: postContent.value,
      media: postMedia.value.length ? JSON.stringify(postMedia.value) : null,
      circleId: Number(route.params.id),
    })
    ElMessage.success('已发布到圈子')
    postContent.value = ''
    postMedia.value = []
    await load()
  } finally {
    posting.value = false
  }
}

// 发活动
function resetActForm() {
  actForm.value = { title: '', capacity: 0, startTime: null, endTime: null }
}

async function submitActivity() {
  if (!actForm.value.title.trim()) {
    ElMessage.warning('请填写活动标题')
    return
  }
  if (!actForm.value.startTime || !actForm.value.endTime) {
    ElMessage.warning('请选择开始和结束时间')
    return
  }
  if (dayjs(actForm.value.endTime).isBefore(actForm.value.startTime)) {
    ElMessage.warning('结束时间需晚于开始时间')
    return
  }
  creatingAct.value = true
  try {
    await createActivity({
      title: actForm.value.title,
      circleId: Number(route.params.id),
      capacity: actForm.value.capacity || 0,
      startTime: actForm.value.startTime.toISOString(),
      endTime: actForm.value.endTime.toISOString(),
    })
    ElMessage.success('活动发布成功')
    showActForm.value = false
    resetActForm()
    tab.value = 'activities'
    await load()
  } finally {
    creatingAct.value = false
  }
}

function actStatusText(s) {
  return ['报名中', '名额已满', '进行中', '已结束', '已取消'][s] || '未知'
}
function actStatusTag(s) {
  return ['primary', 'warning', 'success', 'info', 'danger'][s] || 'info'
}

watch(tab, () => load())
onMounted(load)
</script>

<style scoped>
.cdetail-inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
}
.c-banner {
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--el-color-primary-light-5));
  border-radius: var(--card-radius);
  padding: 20px;
  margin-bottom: 16px;
}
.c-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.c-name { font-size: 22px; font-weight: 700; }
.c-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.c-owner { font-size: 13px; }
.c-count { font-size: 13px; color: var(--el-text-color-secondary); }
.tab-body {
  padding: 8px 0;
}
/* 发帖框 */
.compose-card {
  margin-bottom: 14px;
}
.compose-text {
  margin-bottom: 10px;
}
.compose-media {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}
.media-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
}
.media-thumb {
  width: 80px;
  height: 80px;
  border-radius: 8px;
  object-fit: cover;
}
.compose-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
/* 活动 */
.act-toolbar {
  margin-bottom: 12px;
  display: flex;
  justify-content: flex-end;
}
.act-card {
  margin-bottom: 10px;
  cursor: pointer;
  border-radius: var(--card-radius);
  transition: box-shadow 0.2s;
}
.act-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}
.act-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.act-title { font-weight: 600; }
.act-time { font-size: 13px; color: var(--el-text-color-secondary); margin: 6px 0; }
.act-foot { font-size: 13px; }
.cap-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
