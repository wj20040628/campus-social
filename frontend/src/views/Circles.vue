<template>
  <div class="circles">
    <!--
      顶栏工具条(设计 §1 行 1)。
      全站 TopBar 的发布按钮在手机端是隐藏的(TabBar 中位已有),所以
      「创建圈子」必须由本页自己提供 —— 否则列表非空时用户**没有任何
      创建入口**,只有一个在空态里露面的按钮。
    -->
    <div class="circles__tools">
      <label class="sr-only" for="circle-search">搜圈子</label>
      <input
        id="circle-search"
        v-model="keyword"
        class="circles__search"
        type="search"
        placeholder="搜圈子名"
        @keyup.enter="runSearch"
        @search="runSearch"
      />
      <button
        type="button"
        class="circles__search-go"
        :disabled="searching || !keyword.trim()"
        @click="runSearch"
      >
        {{ searching ? '搜着' : '搜索' }}
      </button>
      <button
        v-if="isSearching"
        type="button"
        class="circles__search-clear"
        @click="clearSearch"
      >
        清空
      </button>
      <button
        type="button"
        class="circles__create"
        aria-label="创建圈子"
        @click="onCreateClick"
      >
        <el-icon :size="20"><Plus /></el-icon>
        <span class="circles__create-text">创建圈子</span>
      </button>
    </div>

    <!-- 首屏骨架:6 条圈子行(48px 方块 + 两行文字条) -->
    <div v-if="!loaded && !loadFailed" class="circles__skeleton" aria-hidden="true">
      <SkeletonList variant="audit" :count="6" />
    </div>

    <!--
      列表加载失败:整页替换 + 重试(设计 §3 错误态)。
      只有首屏失败才到这里;追加翻页失败保留已有内容(见 useCircles.load)。
    -->
    <StateBlock
      v-else-if="loadFailed"
      variant="error"
      title="圈子列表没拉出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load"
    />

    <!-- 搜索态:结果与搜索失败是两种状态,主句不同 -->
    <template v-else-if="isSearching">
      <StateBlock
        v-if="searchFailed"
        variant="error"
        title="没搜到"
        description="搜的时候网络断了，再试一次"
        action-label="重试"
        :icon="WarningFilled"
        @action="runSearch"
      />
      <StateBlock
        v-else-if="!results.length"
        variant="empty"
        title="没找到叫这个名字的圈子"
        description="换个词，或者自己建一个"
        action-label="创建圈子"
        :icon="Plus"
        @action="onCreateClick"
      />
      <div v-else class="circle-rows">
        <CircleRow
          v-for="c in results"
          :key="`s-${c.id}`"
          :circle="c"
          @open="goDetail"
          @join="joinCircle"
        />
      </div>
    </template>

    <template v-else>
      <!--
        移动端:默认只露三个入口按钮(我创建的 / 我加入的 / 全部圈子),点了才展开对应列表;
        不默认加载全部圈子。桌面端恒「多一个分组」直显,不加按钮。
      -->
      <div v-if="isMobile && mobileView === null" class="circles__entries">
        <button
          type="button"
          class="entry"
          @click="openCreated"
        >
          <el-icon :size="20"><User /></el-icon>
          <span class="entry__title">我创建的</span>
          <span class="entry__count ys-num">{{ createdCircles.length }}</span>
          <el-icon :size="18" class="entry__caret"><ArrowRight /></el-icon>
        </button>
        <button
          type="button"
          class="entry"
          @click="openJoined"
        >
          <el-icon :size="20"><ChatDotRound /></el-icon>
          <span class="entry__title">我加入的</span>
          <span class="entry__count ys-num">{{ joinedCircles.length }}</span>
          <el-icon :size="18" class="entry__caret"><ArrowRight /></el-icon>
        </button>
        <button
          type="button"
          class="entry"
          @click="openAll"
        >
          <el-icon :size="20"><Search /></el-icon>
          <span class="entry__title">全部圈子</span>
          <span class="entry__count ys-num">{{ circles.length }}</span>
          <el-icon :size="18" class="entry__caret"><ArrowRight /></el-icon>
        </button>
      </div>

      <!--
        移动端:各列表组按 mobileView 展开,分组头带「← 返回」回到入口按钮组(需求 2)。
        桌面端(isMobile===false)恒渲染三个分组,不受 mobileView 控制。
      -->
      <transition-group name="circles-view">
        <section
          v-if="(isMobile && mobileView === 'created') || !isMobile"
          key="view-created"
          class="circles__group"
        >
          <h2 class="circles__group-head">
            <button
              v-if="isMobile"
              type="button"
              class="circles__group-back"
              @click="closeMobileView"
            >← 返回</button>
            <span class="circles__group-title">我创建的</span>
            <span class="circles__group-count">{{ createdCircles.length }}</span>
          </h2>
          <div v-if="createdCircles.length" class="circle-rows">
            <CircleRow
              v-for="c in createdCircles"
              :key="`cr-${c.id}`"
              :circle="c"
              :leaving="isLeaving(c)"
              @open="goDetail"
              @join="joinCircle"
              @leave="leaveCircle"
            />
          </div>
          <p v-else class="circles__group-empty">还没有当过圈主的圈子</p>
        </section>

        <section
          v-if="(isMobile && mobileView === 'joined') || !isMobile"
          key="view-joined"
          class="circles__group"
        >
          <h2 class="circles__group-head">
            <button
              v-if="isMobile"
              type="button"
              class="circles__group-back"
              @click="closeMobileView"
            >← 返回</button>
            <span class="circles__group-title">我加入的</span>
            <span class="circles__group-count">{{ joinedCircles.length }}</span>
          </h2>
          <div v-if="joinedCircles.length" class="circle-rows">
            <CircleRow
              v-for="c in joinedCircles"
              :key="`j-${c.id}`"
              :circle="c"
              :leaving="isLeaving(c)"
              @open="goDetail"
              @join="joinCircle"
              @leave="leaveCircle"
            />
          </div>
          <p v-else class="circles__group-empty">还没有加入过别人的圈子</p>
        </section>

        <section
          v-if="(isMobile && mobileView === 'all') || !isMobile"
          key="view-all"
          class="circles__group"
        >
          <h2 class="circles__group-head">
            <button
              v-if="isMobile"
              type="button"
              class="circles__group-back"
              @click="closeMobileView"
            >← 返回</button>
            <span class="circles__group-title">全部圈子</span>
            <span class="circles__group-count">{{ circles.length }}</span>
          </h2>
          <div v-if="circles.length" class="circle-rows">
            <CircleRow
              v-for="c in circles"
              :key="`a-${c.id}`"
              :circle="c"
              :leaving="isLeaving(c)"
              @open="goDetail"
              @join="joinCircle"
              @leave="leaveCircle"
            />
          </div>
        </section>
      </transition-group>

      <!-- AC-19:空态必须有可点出口。冷启动一句主句就够,搜索无结果另有一句 -->
      <StateBlock
        v-if="circles.length === 0"
        variant="empty"
        title="还没有圈子"
        description="建一个篮球圈、考研圈或者你们院的圈子，名字你定"
        action-label="创建圈子"
        :icon="Plus"
        @action="onCreateClick"
      />
    </template>

    <!-- 无限滚动哨兵。搜索态下不参与(搜索是一次性结果) -->
    <div v-if="!isSearching" class="sentinel">
      <span v-if="loading" class="circles__more">加载中</span>
      <span v-else-if="!hasMore && circles.length" class="circles__more">到底了</span>
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

    <!-- AC-11:未认证建圈子。给「为什么 + 怎么做」,不给沉默失败 -->
    <ActionSheet v-model="verifyOpen" title="创建圈子">
      <div class="vfy">
        <p class="vfy__why">要先认证校内身份才能建圈子</p>
        <p class="vfy__how">认证只需要校园邮箱或学号，一分钟就好</p>
        <button type="button" class="vfy__btn" @click="goVerify">去认证</button>
      </div>
    </ActionSheet>
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
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, WarningFilled, User, ArrowRight, ChatDotRound, Search } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import CircleRow from '@/components/common/CircleRow.vue'
import { useCircles } from '@/composables/useCircles'
import { useIsMobile } from '@/composables/useBreakpoint'

const router = useRouter()
const verifyOpen = ref(false)
const isMobile = useIsMobile()

const {
  circles, hasMore, loading, loadFailed, loaded,
  keyword, searching, searchFailed, results, isSearching,
  joinedCircles, createdCircles, mobileView, openCreated, openJoined, openAll, closeMobileView,
  showCreate, newName, creating, createError,
  openCreate, load, runSearch, clearSearch, joinCircle, leaveCircle, isLeaving, create,
} = useCircles()

let observer = null

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

/** AC-11:未认证时弹说明而不是直接开抽屉 —— 填完名字才被拦等于白填 */
function onCreateClick() {
  if (openCreate() === 'need-verify') verifyOpen.value = true
}

function goVerify() {
  verifyOpen.value = false
  router.push('/settings')
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