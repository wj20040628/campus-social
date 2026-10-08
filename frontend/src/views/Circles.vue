<template>
  <div class="circles">
    <div class="circles-inner">
      <!--
        「我加入的」与「全部圈子」两个分组(设计 §1)。
        移动端分组是因为列表变长了,分组给的是「先看我加过的」这个快捷视角;
        桌面端一屏能看到 9-12 个卡片,分组反而增加滚动,所以合并成单一网格。
      -->
      <section v-if="joinedCircles.length" class="circles__group">
        <h2 class="circles__group-head">
          我加入的
          <span class="circles__group-count">{{ joinedCircles.length }}</span>
        </h2>
        <div class="circle-rows">
          <CircleRow
            v-for="c in joinedCircles"
            :key="`j-${c.id}`"
            :circle="c"
            @open="goDetail"
            @join="joinCircle"
          />
        </div>
      </section>

      <section class="circles__group">
        <h2 v-if="joinedCircles.length" class="circles__group-head">
          全部圈子
          <span class="circles__group-count">{{ circles.length }}</span>
        </h2>
        <!--
          hideJoined:已在「我加入的」里出现过的圈子不重复列。
          不去重的话同一个圈子会在一屏里出现两次,用户会以为是两个不同的圈子。
        -->
        <div class="circle-rows">
          <CircleRow
            v-for="c in visibleCircles"
            :key="c.id"
            :circle="c"
            @open="goDetail"
            @join="joinCircle"
          />
        </div>
      </section>

      <!--
        AC-19:空态必须有可点出口。
        搜索无结果与冷启动是两种状态,主句不同 —— 共用一句会让用户以为页面坏了。
      -->
      <StateBlock
        v-if="circles.length === 0"
        variant="empty"
        title="还没有圈子"
        description="建一个篮球圈、考研圈或者你们院的圈子，名字你定"
        action-label="创建圈子"
        :icon="Plus"
        @action="openCreate"
      />
    </div>

    <div class="sentinel">
      <el-text v-if="loading" type="info">加载中…</el-text>
      <el-text v-else-if="!hasMore && circles.length" type="info">到底了</el-text>
    </div>

    <!--
      创建圈子:移动端全屏抽屉(设计 §1.2)。
      原来是无条件 el-dialog width="420px",在 375px 视口下横向溢出 ——
      违反 SPEC §8.1 红线 2「禁止 fullscreen 之外的固定宽弹窗」。
    -->
    <el-drawer
      v-model="showCreate"
      direction="btt"
      size="100%"
      :with-header="false"
      class="circles-drawer"
    >
      <div class="create">
        <div class="create__bar">
          <button type="button" class="create__cancel" @click="showCreate = false">取消</button>
          <h2 class="create__title">创建圈子</h2>
          <button type="button" class="create__submit" :disabled="creating" @click="create">
            {{ creating ? '创建中' : '创建' }}
          </button>
        </div>

        <div class="create__body">
          <label class="create__label" for="circle-name">圈子名称</label>
          <input
            id="circle-name"
            v-model="newName"
            class="create__input"
            type="text"
            maxlength="64"
            placeholder="如 篮球圈 / 考研圈 / 摄影社"
            @keyup.enter="create"
          />
          <p class="create__hint">名字起清楚一点，别人搜得到</p>
          <!-- 错误贴近字段(设计 §3),且**输入的名字必须保留** -->
          <p v-if="createError" class="create__error" role="alert">{{ createError }}</p>
        </div>

        <div class="create__foot">
          <button
            type="button"
            class="create__primary"
            :disabled="creating || !newName.trim()"
            @click="create"
          >
            {{ creating ? '创建中' : '创建' }}
          </button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
/**
 * 圈子列表(Circles.vue.md)
 *
 * 移动端形态是「单列全宽行」而不是卡片网格(设计 §1.1):
 * auto-fill 网格在 375px 下虽然退化成单列,但每行仍带卡片盒子的边框、
 * 圆角与内边距,视觉上是孤立方块堆叠,浪费 32px 垂直空间且不像列表。
 * 桌面端恢复网格(见 CircleRow.vue 内的媒体查询)。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { Plus } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import CircleRow from '@/components/common/CircleRow.vue'
import { list as listApi, join as joinApi, createCircle as createApi } from '@/api/circle'

const router = useRouter()
const circles = ref([])
const cursor = ref(null)
const hasMore = ref(true)
const loading = ref(false)
const showCreate = ref(false)
const newName = ref('')
const creating = ref(false)
/** 创建失败的提示,贴在输入框下方(设计 §3)。不用 toast 是因为抽屉会挡住它 */
const createError = ref('')

let observer = null

/** 「我加入的」分组。空则整组隐藏(v-if 在模板上),不留空分组头 */
const joinedCircles = computed(() => circles.value.filter((c) => c.joined))

/** 「全部圈子」分组里要排除已在上方出现过的,否则同一个圈子一屏出现两次 */
const visibleCircles = computed(() =>
  joinedCircles.value.length
    ? circles.value.filter((c) => !c.joined)
    : circles.value,
)

async function load() {
  loading.value = true
  try {
    // 不传关键字:openapi 里 GET /circle 没有 keyword 参数(已查证)。
    // 搜索能力要等后端补,前端不发明不存在的端点。
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
  try {
    await joinApi(c.id)
    c.joined = true
    ElMessage.success(`已加入「${c.name}」`)
  } catch {
    // 按钮回到「加入」态(c.joined 未改)+ request.js 已弹错(设计 §3)
    ElMessage.closeAll()
  }
}

function openCreate() {
  createError.value = ''
  showCreate.value = true
}

async function create() {
  const name = newName.value.trim()
  if (!name || creating.value) return
  creating.value = true
  createError.value = ''
  try {
    await createApi(name)
    showCreate.value = false
    newName.value = ''
    ElMessage.success('圈子建好了，去发第一条吧')
    await load()
  } catch {
    // 输入的名字**保留** —— 清空等于让用户重打一遍(设计 §3)
    createError.value = '没创建成功，再点一次'
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

<style scoped src="./Circles.css"></style>