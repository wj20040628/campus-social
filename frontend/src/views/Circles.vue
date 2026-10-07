<template>
  <div class="circles">
    <div class="circles-inner">
      <div class="circles-toolbar">
        <span class="circles-title">圈子广场</span>
        <el-button type="primary" :icon="Plus" round @click="showCreate = true">
          创建圈子
        </el-button>
      </div>

      <div class="circle-grid">
        <el-card
          v-for="c in circles"
          :key="c.id"
          shadow="hover"
          class="circle-card"
          @click="goDetail(c.id)"
        >
          <div class="c-top">
            <el-avatar :size="48" :src="resolveUrl(c.owner?.avatarUrl)" class="c-avatar" />
            <div class="c-info">
              <div class="c-name">{{ c.name }}</div>
              <div class="c-sub">{{ c.memberCount }} 成员</div>
            </div>
            <div class="c-join">
              <el-button
                v-if="!c.joined"
                link
                type="primary"
                @click.stop="joinCircle(c)"
              >
                加入
              </el-button>
              <el-tag v-else type="success">已加入</el-tag>
            </div>
          </div>
        </el-card>
        <el-empty v-if="circles.length === 0" description="还没有圈子" :image-size="80" />
      </div>

      <div class="sentinel">
        <el-text v-if="loading" type="info">加载中…</el-text>
        <el-text v-else-if="!hasMore && circles.length" type="info">到底了</el-text>
      </div>
    </div>

    <!-- 创建圈子 -->
    <el-dialog v-model="showCreate" title="创建圈子" width="420px">
      <el-form>
        <el-form-item label="圈子名称">
          <el-input v-model="newName" placeholder="如 篮球圈 / 考研圈 / 摄影社" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  list as listApi,
  join as joinApi,
  createCircle as createApi,
} from '@/api/circle'
import { resolveUrl } from '@/utils/resolve'

const router = useRouter()
const circles = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
let observer = null

const showCreate = ref(false)
const newName = ref('')
const creating = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await listApi(cursor.value, 20)
    circles.value = cursor.value ? [...circles.value, ...data.list] : data.list
    cursor.value = data.hasMore ? data.nextCursor : null
    hasMore.value = data.hasMore
  } finally {
    loading.value = false
  }
}

function setupObserver() {
  observer?.disconnect()
  const el = document.querySelector('.sentinel')
  if (!el) return
  observer = new IntersectionObserver(
    (entries) => {
      if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load()
    },
    { threshold: 0.1, rootMargin: '200px' }
  )
  observer.observe(el)
}

async function joinCircle(c) {
  await joinApi(c.id)
  c.joined = true
  ElMessage.success(`已加入「${c.name}」`)
}

async function create() {
  if (!newName.value.trim()) return
  creating.value = true
  try {
    await createApi(newName.value.trim())
    ElMessage.success('圈子创建成功')
    showCreate.value = false
    newName.value = ''
    await load()
  } finally {
    creating.value = false
  }
}

function goDetail(id) {
  router.push(`/circles/${id}`)
}

onMounted(async () => {
  await load()
  setupObserver()
})

onBeforeUnmount(() => {
  observer?.disconnect()
})
</script>

<style scoped>
.circles-inner {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
}
.circles-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.circles-title {
  font-size: 20px;
  font-weight: 700;
}
.circle-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
  margin: 0;
}
.circle-card {
  cursor: pointer;
  transition: box-shadow 0.2s;
}
.c-top {
  display: flex;
  gap: 12px;
  align-items: center;
}
.c-avatar {
  flex-shrink: 0;
}
.c-info {
  flex: 1;
  min-width: 0;
}
.c-name {
  font-weight: 600;
  font-size: 15px;
}
.c-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.c-join {
  flex-shrink: 0;
}
.sentinel {
  text-align: center;
  padding: 16px;
}
</style>
