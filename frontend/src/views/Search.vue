<template>
  <div class="search">
    <div class="search-inner">
      <!--
        栅格一律 CSS Grid(红线 3 / SPEC §10.3 第 3 条)。
        这里原本是 el-row + el-col :span="14":虽然没用响应式属性,但 EP 的
        24 栅格本身属于 EP 的断点体系,换掉才能保证断点只有一套。
        768 起两列:搜索框 + 按钮,按钮不再被挤到第二行。
      -->
      <div class="search__bar">
        <el-input
          v-model="q"
          class="search__input"
          placeholder="搜索帖子 / 用户 / 圈子"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button @click="doSearch">搜索</el-button>
          </template>
        </el-input>
      </div>

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
            <StateBlock v-if="!(results.posts || []).length" variant="empty" title="没有匹配的帖子" description="换个词试试,或者直接发一条这个话题的" action-label="去发一条" :icon="Search" @action="router.push('/publish')" />
          </div>

          <div v-else-if="tab === 'users'" class="res-list">
            <div v-for="u in results.users || []" :key="u.id" class="user-item" @click="router.push(`/user/${u.id}`)">
              <el-avatar :size="44" :src="resolveUrl(u.avatarUrl)" class="u-avatar" />
              <div class="u-info">
                <div class="u-name">{{ displayName(u) }}</div>
                <div class="u-sub">{{ maskUsername(u?.username) }}</div>
              </div>
            </div>
            <StateBlock v-if="!(results.users || []).length" variant="empty" title="没有匹配的同学" description="关键词可能太长了,试试只输入姓或昵称" action-label="改个关键词" :icon="User" @action="clearKw" />
          </div>

          <div v-else class="res-list">
            <div v-for="c in results.circles || []" :key="c.id" class="user-item" @click="router.push(`/circles/${c.id}`)">
              <el-avatar :size="44" :src="c.avatarUrl ? resolveUrl(c.avatarUrl) : ''" class="u-avatar c-avatar" />
              <div class="u-info">
                <div class="u-name">{{ c.name }}</div>
                <div class="u-sub">{{ c.memberCount || 0 }} 成员</div>
              </div>
            </div>
            <StateBlock v-if="!(results.circles || []).length" variant="empty" title="没有匹配的圈子" description="换个词试试,或者自己开一个" action-label="去圈子广场" :icon="Collection" @action="router.push('/circles')" />
          </div>
        </template>
      </div>

      <StateBlock v-else variant="empty" title="搜搜看谁在聊什么" description="输入昵称、话题或圈子名,三个方向一起搜" :icon="Search" />
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
import StateBlock from '@/components/common/StateBlock.vue'
import { Search, User, Collection } from '@element-plus/icons-vue'

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

/** 「没有匹配的用户」空态的出口:清空关键词重新搜,而不是让用户自己找输入框 */
function clearKw() {
  q.value = ''
  results.value = {}
  searched.value = false
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

<style scoped src="./Search.css"></style>
