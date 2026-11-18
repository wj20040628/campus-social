<template>
  <div class="mkt">
    <div class="mkt-inner">
      <!--
        顶栏工具条（2026-10-21 重做，2026-11-14 再调）。
        改版前这里是「发布按钮独占一行」+ 三行分类 chip ——
        手机上四行全是控制项，没有一个像素是内容。
        2026-10-21：发布按钮与分类筛选放到同一行、分类单行横滑。
        2026-11-14：保持单行 chip，不加「更多」入口或箭头。
      -->
      <div class="mkt__tools">
        <CategoryChips
          v-if="tab === 'plaza'"
          :model-value="category"
          :options="categoryOptions"
          aria-label="活动分类"
          @change="pickCategory"
        />

        <button type="button" class="mkt__publish" @click="goPublish">
          <el-icon :size="16"><Plus /></el-icon>
          <span>发布</span>
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

        <el-tab-pane v-if="userStore.hasToken" label="我的发布" name="my">
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
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Plus } from '@element-plus/icons-vue'
import CategoryChips from '@/components/common/CategoryChips.vue'
import { useUserStore } from '@/stores/user'
import { useMktPublish } from '@/composables/useMktPublish'
import MktList from '@/components/mkt/MktList.vue'
import { PLAZA_FILTERS } from '@/components/mkt/mktConstants'
import { useMktOwnerActions } from '@/composables/useMktOwnerActions'
import * as mkt from '@/api/mkt'

const router = useRouter()
const userStore = useUserStore()
const { goPublish } = useMktPublish()

const tab = ref('plaza')

/* ---------------- 广场 ---------------- */
const plazaListData = ref([])
const plazaHasMore = ref(false)
const plazaLoading = ref(false)
const plazaFailed = ref(false)
const category = ref(null)

/**
 * 分类切换（2026-10-21 修）。
 *
 * 改版前的分类列表里**没有「全部」**，而这里只做 `category.value = c`，
 * 于是用户点了一个具体分类之后，无法回到「看全部」的状态 —— 只能刷新页面。
 * 现在 PLAZA_FILTERS 的第一项是 { label:'全部', value:null }，
 * 点它就是取消筛选（传 null 给后端 = 不加 category 条件）。
 */
function pickCategory(c) {
  category.value = c
  loadPlaza()
}

/**
 * 各分类下的活动条数（选择面板里分类右侧的小数字）。
 *
 * 【口径】只统计**已加载的**广场列表（一页 20 条），未翻完时数字会偏小 ——
 * 所以只在 > 0 时显示，且用弱化色，它是提示而非权威总数。
 * 传 null 返回全部条数，与「全部」的语义一致。
 */
function countOf(v) {
  if (v == null) return plazaListData.value.length
  return plazaListData.value.filter((a) => a.category === v).length
}

const categoryOptions = computed(() =>
  PLAZA_FILTERS.map((c) => ({ value: c.value, label: c.label, count: countOf(c.value) }))
)

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

/* 属主操作:两处列表都要刷 —— 只刷「我的发布」时，切回广场仍看到已删除/已暂停的旧卡片 */
const { edit, toggle, full, reactivate, remove } = useMktOwnerActions(() => {
  loadMy()
  loadPlaza()
})

/* 发布入口的游客守卫见 useMktPublish；发布频率、信誉分与在投配额由后端判定 */
function goDetail(id) {
  router.push(`/mkt/${id}`)
}

onMounted(() => {
  loadPlaza()
  // 「我的发布」要登录态；游客没有这个 Tab，不必白跑一次 401
  if (userStore.hasToken) loadMy()
})
</script>

<style scoped src="./MktPlaza.css"></style>
