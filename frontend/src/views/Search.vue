<template>
  <div class="search">
    <div class="search-inner">
      <el-row :gutter="16">
        <el-col :span="14">
          <el-input
            v-model="q"
            placeholder="搜索帖子 / 用户 / 圈子"
            clearable
            @keyup.enter="doSearch"
          >
            <template #append>
              <el-button @click="doSearch">搜索</el-button>
            </template>
          </el-input>
        </el-col>
      </el-row>

      <div v-if="searched">
        <el-tabs v-model="tab">
          <el-tab-pane label="帖子" name="posts" />
          <el-tab-pane label="用户" name="users" />
          <el-tab-pane label="圈子" name="circles" />
        </el-tabs>

        <!-- 搜索中骨架占位,避免点击后到出结果之间纯白 -->
        <div v-if="searching" class="res-list">
          <el-card v-for="i in 3" :key="'sk' + i">
            <el-skeleton :rows="2" animated />
          </el-card>
        </div>

        <template v-else>
          <div v-if="tab === 'posts'" class="res-list">
            <PostCard v-for="p in results.posts || []" :key="p.id" :post="p" @click="router.push(`/post/${p.id}`)" />
            <el-empty v-if="!(results.posts || []).length" description="没有匹配的帖子" :image-size="80" />
          </div>

          <div v-else-if="tab === 'users'" class="res-list">
            <div v-for="u in results.users || []" :key="u.id" class="user-item" @click="router.push(`/user/${u.id}`)">
              <el-avatar :size="44" :src="resolveUrl(u.avatarUrl)" class="u-avatar" />
              <div class="u-info">
                <div class="u-name">{{ displayName(u) }}</div>
                <div class="u-sub">{{ maskUsername(u?.username) }}</div>
              </div>
            </div>
            <el-empty v-if="!(results.users || []).length" description="没有匹配的用户" :image-size="80" />
          </div>

          <div v-else class="res-list">
            <div v-for="c in results.circles || []" :key="c.id" class="user-item" @click="router.push(`/circles/${c.id}`)">
              <el-avatar :size="44" :src="c.avatarUrl ? resolveUrl(c.avatarUrl) : ''" class="u-avatar c-avatar" />
              <div class="u-info">
                <div class="u-name">{{ c.name }}</div>
                <div class="u-sub">{{ c.memberCount || 0 }} 成员</div>
              </div>
            </div>
            <el-empty v-if="!(results.circles || []).length" description="没有匹配的圈子" :image-size="80" />
          </div>
        </template>
      </div>

      <el-empty v-else description="输入关键词开始搜索" :image-size="100" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { search as searchApi } from '@/api/circle'
import { maskUsername, displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import PostCard from '@/components/PostCard.vue'

const route = useRoute()
const router = useRouter()
const q = ref('')
const tab = ref('posts')
const searched = ref(false)
const searching = ref(false)
const results = ref({})

async function doSearch() {
  const kw = q.value.trim()
  if (!kw) return
  searching.value = true
  try {
    results.value = await searchApi(kw, 'all', 20)
  } catch {
    results.value = {}
  } finally {
    searched.value = true
    searching.value = false
  }
}

// 从首页搜索框 / 话题跳来:读 ?kw 预填并自动搜
onMounted(() => {
  const kw = route.query.kw
  if (kw) {
    q.value = String(kw)
    doSearch()
  }
})
</script>

<style scoped>
.search-inner {
  max-width: 720px;
  margin: 0 auto;
  padding: 20px;
}
.res-list {
  padding: 8px 0;
}
.user-item {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 14px 8px;
  margin: 0 -8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  border-radius: var(--card-radius);
  transition: background 0.15s;
}
.user-item:hover {
  background: var(--el-fill-color-light);
}
.u-avatar {
  flex-shrink: 0;
}
.c-avatar {
  background: var(--el-color-primary-light-8);
}
.u-info {
  flex: 1;
  min-width: 0;
}
.u-name { font-weight: 600; }
.u-sub { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
