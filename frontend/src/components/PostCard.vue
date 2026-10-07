<template>
  <el-card class="post-card" shadow="hover" @click="$emit('click', post)">
    <div class="post-head">
      <el-avatar
        :size="40"
        :src="resolveUrl(post.author?.avatarUrl)"
        class="post-avatar"
        @click.stop="goAuthor"
      />
      <div class="post-user" @click.stop="goAuthor">
        <div class="post-nick">{{ post.author ? displayName(post.author) : '匿名' }}</div>
        <div class="post-time">{{ post.createTime }}</div>
      </div>
      <el-tag v-if="post.status === 0" type="warning" size="small">审核中</el-tag>
      <el-tag v-else-if="post.status === 2" type="danger" size="small">已驳回</el-tag>
      <el-tag v-else-if="post.circleId" type="info" size="small">圈子</el-tag>
    </div>

    <div class="post-body">
      <p class="post-text">{{ post.content }}</p>
      <div v-if="post.topics?.length" class="post-topics">
        <el-tag
          v-for="t in post.topics"
          :key="t"
          size="small"
          type="primary"
          effect="plain"
          class="topic-tag"
          @click.stop="goTopic(t)"
        >
          #{{ t }}
        </el-tag>
      </div>
      <div v-if="mediaList.length" class="post-media">
        <template v-for="(m, i) in mediaList" :key="i">
          <el-image
            v-if="m.type === 'img'"
            :src="resolveUrl(m.url)"
            :preview-src-list="imgList"
            :initial-index="i"
            fit="cover"
            class="post-img"
            loading="lazy"
          />
          <video v-else :src="resolveUrl(m.url)" controls class="post-video" :poster="resolveUrl(m.url)"></video>
        </template>
      </div>
    </div>

    <div class="post-foot">
      <span class="post-meta"><el-icon><Star /></el-icon> {{ post.likeCount || 0 }}</span>
      <span class="post-meta"><el-icon><ChatLineRound /></el-icon> {{ post.commentCount || 0 }}</span>
      <span class="post-meta"><el-icon><View /></el-icon> {{ post.viewCount || 0 }}</span>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { Star, ChatLineRound, View } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

const router = useRouter()
const props = defineProps({
  post: { type: Object, required: true },
})
defineEmits(['click', 'topic'])

function goAuthor() {
  if (props.post.author?.id) router.push(`/user/${props.post.author.id}`)
}

// 点话题标签 → 话题落地页
function goTopic(name) {
  router.push(`/topic/${encodeURIComponent(name)}`)
}

const mediaList = computed(() => {
  if (!props.post.media) return []
  // media 可能是 JSON 字符串或已解析
  let m = props.post.media
  if (typeof m === 'string') {
    try { m = JSON.parse(m) } catch { return [] }
  }
  return Array.isArray(m) ? m : []
})

const imgList = computed(() =>
  mediaList.value.filter((x) => x.type === 'img').map((x) => resolveUrl(x.url))
)
</script>

<style scoped>
.post-card {
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.post-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.post-user .post-nick {
  font-weight: 600;
  font-size: 15px;
}
.post-user .post-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.post-avatar,
.post-user {
  cursor: pointer;
}
.post-avatar:hover {
  opacity: 0.85;
}
.post-user .post-nick:hover {
  color: var(--el-color-primary);
}
.post-body {
  margin: 12px 0;
}
.post-text {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
}
.post-topics {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.topic-tag {
  cursor: pointer;
}
.topic-tag:hover {
  opacity: 0.8;
}
.post-media {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}
.post-img {
  width: 180px;
  height: 180px;
  border-radius: 8px;
}
.post-video {
  width: 100%;
  max-width: 360px;
  border-radius: 8px;
}
.post-foot {
  display: flex;
  gap: 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.post-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.post-meta .el-icon {
  font-size: 15px;
  color: var(--el-text-color-secondary);
}
</style>
