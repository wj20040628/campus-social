<template>
  <div class="mkt">
    <div class="mkt-inner">
      <!--
        顶栏工具条：发布入口（广场/我的发布共用的创建路径）+ 一级分类过滤 chip。
        发布入口必须常露 —— 不能只在空态里给,否则列表非空时没有任何创建路径。
      -->
      <div class="mkt__tools">
        <button type="button" class="mkt__publish" @click="goPublish">
          <el-icon :size="18"><Plus /></el-icon>
          <span>发布活动</span>
        </button>
      </div>

      <!-- 一级分类过滤:9 选 1。只对广场生效,「我的发布」不受过滤影响。 -->
      <div v-if="tab === 'plaza'" class="mkt__chips" role="tablist" aria-label="一级分类">
        <button
          type="button"
          v-for="c in CATEGORIES"
          :key="c"
          class="mkt__chip"
          :class="{ 'mkt__chip--on': category === c }"
          :aria-pressed="category === c"
          @click="pickCategory(c)"
        >
          {{ c }}
        </button>
      </div>

      <!-- 广场 / 我的发布 两个 Tab -->
      <el-tabs v-model="tab" class="mkt__tabs">
        <el-tab-pane label="广场" name="plaza">
          <MktList
            variant="plaza"
            :loading="plazaLoading"
            :list="plazaListData"
            :has-more="plazaHasMore"
            :failed="plazaFailed"
            @open="goDetail"
            @load="loadPlaza"
            @load-more="loadPlazaMore"
            @empty-action="goPublish"
            @retry="loadPlaza"
          />
        </el-tab-pane>

        <el-tab-pane label="我的发布" name="my">
          <MktList
            variant="my"
            :loading="myLoading"
            :list="myListData"
            :has-more="myHasMore"
            :failed="myFailed"
            @open="goDetail"
            @edit="edit"
            @toggle="toggle"
            @full="full"
            @reactivate="reactivate"
            @delete="remove"
            @load="loadMy"
            @load-more="loadMyMore"
            @empty-action="goPublish"
            @retry="loadMy"
          />
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
/**
 * 校园限时活动集市 · 广场 + 我的发布
 * ------------------------------------------------------------------
 * 两个 Tab 共用同一套卡片与分页基建,差异只在:
 *   广场 —— 纯查询过滤(投放中/未满员/未过期),可一级分类过滤;
 *   我的发布 —— 我未软删的全部活动,带服务端 4 态 status + 属主操作组。
 * 形态差异(卡片左侧状态灯 / 右侧操作组)收在 MktList → MktCard 内部,
 * 本页只负责编排请求与两个 Tab 的独立游标。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Plus } from '@element-plus/icons-vue'
import MktList from '@/components/mkt/MktList.vue'
import { CATEGORIES } from '@/components/mkt/mktConstants'
import { useMktOwnerActions } from '@/composables/useMktOwnerActions'
import * as mkt from '@/api/mkt'

const router = useRouter()

const tab = ref('plaza')

/* ---------------- 广场 ---------------- */
const plazaListData = ref([])
const plazaHasMore = ref(false)
const plazaLoading = ref(false)
const plazaFailed = ref(false)
const category = ref(null)

function pickCategory(c) {
  category.value = c
  loadPlaza()
}

async function loadPlaza() {
  plazaLoading.value = true
  plazaFailed.value = false
  try {
    const data = await mkt.mktPlaza(category.value, null, 20)
    plazaListData.value = data.list || []
    plazaHasMore.value = !!data.hasMore
    plazaCursor = data.nextCursor
  } catch {
    plazaFailed.value = true
  } finally {
    plazaLoading.value = false
  }
}

let plazaCursor = null
async function loadPlazaMore() {
  if (!plazaHasMore.value || plazaLoading.value) return
  plazaLoading.value = true
  try {
    const data = await mkt.mktPlaza(category.value, plazaCursor, 20)
    plazaListData.value = [...plazaListData.value, ...(data.list || [])]
    plazaHasMore.value = !!data.hasMore
    plazaCursor = data.nextCursor
  } catch {
    ElMessage.error('加载失败,再点一次')
  } finally {
    plazaLoading.value = false
  }
}

/* ---------------- 我的发布 ---------------- */
const myListData = ref([])
const myHasMore = ref(false)
const myLoading = ref(false)
const myFailed = ref(false)
let myCursor = null

async function loadMy() {
  myLoading.value = true
  myFailed.value = false
  try {
    const data = await mkt.mktMy(null, 20)
    myListData.value = data.list || []
    myHasMore.value = !!data.hasMore
    myCursor = data.nextCursor
  } catch {
    myFailed.value = true
  } finally {
    myLoading.value = false
  }
}

async function loadMyMore() {
  if (!myHasMore.value || myLoading.value) return
  myLoading.value = true
  try {
    const data = await mkt.mktMy(myCursor, 20)
    myListData.value = [...myListData.value, ...(data.list || [])]
    myHasMore.value = !!data.hasMore
    myCursor = data.nextCursor
  } catch {
    ElMessage.error('加载失败,再点一次')
  } finally {
    myLoading.value = false
  }
}

/* 属主操作:成功后 reloadMy 刷新「我的发布」 */
const { edit, toggle, full, reactivate, remove } = useMktOwnerActions(loadMy)

/* ---------------- 导航 ---------------- */
function goPublish() {
  router.push('/mkt/publish')
}

function goDetail(id) {
  router.push(`/mkt/${id}`)
}

onMounted(() => {
  loadPlaza()
  loadMy()
})
</script>

<style scoped>
.mkt-inner {
  max-width: 1180px;
  margin: 0 auto;
}

/* ---------------- 工具条 ---------------- */
.mkt__tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
}

.mkt__publish {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.mkt__publish:hover {
  background: var(--ys-accent-hover);
}

/* ---------------- 分类 chip ---------------- */
.mkt__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
}

.mkt__chip {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out),
    color var(--ys-motion-fast) var(--ys-ease-out);
}

.mkt__chip:hover {
  background: var(--ys-surface-2);
}

.mkt__chip--on {
  border-color: var(--ys-accent);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}

/* Tab 头去掉下边框,和卡片网格视觉更连贯 */
.mkt__tabs :deep(.el-tabs__item) {
  font-size: var(--ys-text-base);
}
</style>
