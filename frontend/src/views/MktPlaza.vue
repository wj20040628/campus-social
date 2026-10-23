<template>
  <div class="mkt">
    <div class="mkt-inner">
      <!--
        顶栏工具条（2026-10-21 重做）。
        改版前这里是「发布按钮独占一行（justify-content:flex-end）」+ 下面三行分类 chip ——
        手机上四行全是控制项，没有一个像素是内容。
        现在：发布按钮与分类筛选**同一行**，分类单行横滑。
      -->
      <div class="mkt__tools">
        <!-- 分类筛选：单行横滑，「全部」在最前（修「点了就回不去」） -->
        <div
          v-if="tab === 'plaza'"
          ref="chipsEl"
          class="mkt__chips"
          :class="{ 'is-end': chipsAtEnd }"
          role="tablist"
          aria-label="一级分类"
          @scroll="onChipsScroll"
        >
          <button
            v-for="c in PLAZA_FILTERS"
            :key="String(c.value)"
            type="button"
            role="tab"
            class="mkt__chip"
            :class="{ 'mkt__chip--on': category === c.value }"
            :aria-selected="category === c.value"
            @click="pickCategory(c.value)"
          >
            {{ c.label }}
            <!-- 只在「全部」与当前有数据的分类上显示计数，避免一排全 0 -->
            <span v-if="countOf(c.value) > 0" class="mkt__chip-n ys-num">{{ countOf(c.value) }}</span>
          </button>
        </div>

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
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { isIdentityVerified } from '@/utils/mask'
import MktList from '@/components/mkt/MktList.vue'
import { PLAZA_FILTERS } from '@/components/mkt/mktConstants'
import { useMktOwnerActions } from '@/composables/useMktOwnerActions'
import * as mkt from '@/api/mkt'

const router = useRouter()
/* 未认证拦截用：判据读 userStore.userInfo.verified（见 isIdentityVerified） */
const userStore = useUserStore()

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
 * 改版前的 chip 列表里**没有「全部」**，而这里只做 `category.value = c`，
 * 于是用户点了一个具体分类之后，无法回到「看全部」的状态 —— 只能刷新页面。
 * 现在 PLAZA_FILTERS 的第一项是 { label:'全部', value:null }，
 * 点它就是取消筛选（传 null 给后端 = 不加 category 条件）。
 */
function pickCategory(c) {
  category.value = c
  loadPlaza()
}

/**
 * 各分类下的活动条数（chip 右侧的小数字）。
 *
 * 【口径】只统计**已加载的**广场列表（一页 20 条），未翻完时数字会偏小 ——
 * 所以只在 > 0 时显示，且用弱化色，它是提示而非权威总数。
 * 传 null 返回全部条数，与「全部」chip 的语义一致。
 */
function countOf(v) {
  if (v == null) return plazaListData.value.length
  return plazaListData.value.filter((a) => a.category === v).length
}

/**
 * 分类 chip 横滑条已到最右端 —— 到了就取消右侧渐隐，
 * 避免「一直暗示可滑却滑不动」。与公告列表页同一套做法。
 */
const chipsEl = ref(null)
const chipsAtEnd = ref(false)

function onChipsScroll() {
  const el = chipsEl.value
  if (!el) return
  chipsAtEnd.value = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1
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
/**
 * 去发布。未认证时前端先拦（2026-10-21）。
 * 后端已挂 @RequireVerified，但等用户填完整张表单才在提交时被拒是最伤人的 ——
 * 集市表单字段多，填完才说「你得先认证」，这份输入就白费了。
 */
function goPublish() {
  if (!isIdentityVerified(userStore.userInfo)) {
    ElMessage.warning('完成校内认证后才能发布集市活动')
    return
  }
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

/* ---------------- 工具条（2026-10-21 重做） ---------------- */
/*
 * 改版前：.mkt__tools 是 justify-content:flex-end 的独立一行，发布按钮独占 44px；
 * 下面是 flex-wrap 的分类 chip，手机上折成 3 行 148px —— 实测 chipTops=[125,177,229]。
 * 四行控制项吃掉视口 844 的约 23%，留给活动列表的空间不够。
 *
 * 现在：发布按钮与分类筛选同一行（左侧横滑分类吃满剩余宽度，右侧发布按钮固定），
 * 分类单行不折行。整块从 192px 降到约 44px。
 */
.mkt__tools {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
  /*
   * min-width:0 必须写：这是 flex 容器的常见坑 ——
   * 里面有一个「横滑的 chip 条」，它自己也要 min-width:0 才能被压缩。
   * 少写一个，chip 条会用内容宽度把容器撑开，右侧的发布按钮就被挤出去/压上来
   * （实测第一版：发布按钮压在「拼车出行」上面）。
   */
  min-width: 0;
}

/* ---------------- 分类 chip ---------------- */
/*
 * 右侧渐隐提示「还能往右滑」。
 * 宽度踩过两次坑：16px/24px 都会把整个 chip 淡成半透明，看起来像「坏了」
 * 而不是「可滑」。8px 只抹掉最边上一点点，够表达「右边还有」。
 */
.mkt__chips {
  display: flex;
  flex: 1;
  min-width: 0;
  gap: var(--ys-space-2);
  /* 单行横滑，不折行 */
  flex-wrap: nowrap;
  overflow-x: auto;
  scrollbar-width: none;
  padding: 2px var(--ys-space-2) 2px 0;
  -webkit-mask-image: linear-gradient(to right, #000 0, #000 calc(100% - 8px), transparent 100%);
  mask-image: linear-gradient(to right, #000 0, #000 calc(100% - 8px), transparent 100%);
}

/* 滑到最右取消渐隐（否则到底了还一直暗示可滑） */
.mkt__chips.is-end {
  -webkit-mask-image: none;
  mask-image: none;
}

.mkt__chips::-webkit-scrollbar {
  display: none;
}

.mkt__chip {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
  white-space: nowrap;
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
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-weight: var(--ys-weight-semibold);
}

/* chip 内的计数：激活态白字上也要能看清，用 opacity 弱化而不换色 */
.mkt__chip-n {
  font-size: var(--ys-text-xs);
  font-variant-numeric: tabular-nums;
  opacity: 0.75;
}

.mkt__publish {
  /*
   * flex:none —— 发布按钮不许被压缩。
   * 改版第一版没写它，于是 chip 条（flex:1）把剩余空间全吃掉后，
   * 按钮被压到压在 chip 上面（实测截图里「发布」压在「拼车出行」上）。
   * 加了 flex:none 后按钮保持自身宽度，chip 条只占剩下的空间。
   */
  flex: none;
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

/* Tab 头去掉下边框,和卡片网格视觉更连贯 */
.mkt__tabs :deep(.el-tabs__item) {
  font-size: var(--ys-text-base);
}

/* ---------------------------------------------------------------- 桌面端 */
/*
 * 2026-10-21 加。
 *
 * 实测改版前 .mkt-inner 是 max-width:1180。但 **MktList 自己已经有响应式网格**：
 *   <768 单列 / 768–1279 两列 / ≥1280 四列（MktList.vue 内联样式）
 * 也就是说桌面端一直是多列的，我最初设计的「改成三列」是多余的 ——
 * 真正的问题只是 1180 这个容器宽度让每张卡偏宽、且左对齐留白不均衡。
 *
 * 所以这里只做一件事：把内容列收到 1000 并让 .mkt-inner 在宽屏上居中。
 * 不覆盖 MktList 的列数，避免与它内部的三档断点打架。
 */
@media (min-width: 1024px) {
  .mkt-inner {
    max-width: 1000px;
  }
}
</style>
