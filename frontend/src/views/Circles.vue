<template>
  <div class="circles">
    <!--
      工具条(设计稿场景 4,C11-C13):一行只有两个可点物 —— 搜索框、创建圈子。
      输入即搜(300ms 防抖,见 useCircles 的 watch),回车立即搜;
      清空是输入框内的小叉,不占行内位置,不会把别的按钮顶掉。
      一个圈都没有且不在搜索时整条不出现:没有东西可搜,创建交给空态按钮,
      否则空页上会并排挂着两个「创建圈子」。
    -->
    <div v-if="!loaded || circles.length || isSearching" class="circles__tools">
      <label class="sr-only" for="circle-search">搜圈子</label>
      <div class="circles__searchbox">
        <input
          id="circle-search"
          v-model="keyword"
          class="circles__search"
          type="search"
          placeholder="搜圈子名"
          @keyup.enter="runSearch"
        />
        <button
          v-if="keyword"
          type="button"
          class="circles__search-x"
          aria-label="清空搜索"
          @click="clearSearch"
        >
          &#10005;
        </button>
      </div>
      <button type="button" class="circles__create" @click="onCreateClick">
        <el-icon :size="18"><Plus /></el-icon>
        <span>创建圈子</span>
      </button>
    </div>

    <!-- 首屏骨架:6 条圈子行 -->
    <div v-if="!loaded && !loadFailed" class="circles__skeleton" aria-hidden="true">
      <SkeletonList variant="audit" :count="6" />
    </div>

    <!-- 首屏拉取失败:整页替换 + 重试。追加翻页失败不清列表(useCircles.load) -->
    <StateBlock
      v-else-if="loadFailed"
      variant="error"
      title="圈子列表没拉出来"
      description="可能是网络断了，点一下重试"
      action-label="重试"
      :icon="WarningFilled"
      @action="load"
    />

    <!-- 搜索态:结果 / 无结果 / 搜索失败三种,主句各不同 -->
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
        v-else-if="!searching && !results.length"
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
        一个圈都没有:整页只给一块空态(AC-19 出口由它的按钮承担)。
        改版前这里同时渲染「我的」空态、「发现」空态和这块整页空态 ——
        一屏三段「没有圈子」,而且「发现」在零圈子时会写出「都加过了」
        这种与事实相反的句子,「去发现」也只会滚到另一块空态上。
      -->
      <StateBlock
        v-if="!circles.length"
        variant="empty"
        title="还没有圈子"
        description="建一个篮球圈、考研圈，等同学来加入"
        action-label="创建圈子"
        :icon="Plus"
        @action="onCreateClick"
      />

      <template v-else>
        <!--
          C1/C4/C5/C15/C17:手机桌面同一套结构,两段「我的 / 发现」直接铺开。
          「我的」= 我参与的(圈主/管理/成员都算,身份看卡片小标签);
          「发现」= 剩下的。两段互斥、覆盖全集,同一个圈一页只出现一次。

          「我的」为空时整段不渲染(2026-10-27):空着也占一个「我的 0」标题
          和一块空态,会把真正的发现列表推到首屏之外;「我还一个都没加」
          这件事,下面的发现列表本身已经在回答。
        -->
        <section v-if="myCircles.length" class="circles__group">
          <h2 class="circles__group-head">
            <span class="circles__group-title">我的</span>
            <span class="circles__group-count">{{ myCircles.length }}</span>
            <!--
              「管理 N / 管 N」指示器已移除（2026-10-21）。
              它表示「我的圈里有几个是我在管（圈主或圈管理）」，本意是区分
              「参与了 10 个 / 其中 3 个要管」。但两处信息已经覆盖了它：
                1. 每张卡片标题行都带角色标签（圈主 / 圈管理 / 成员）；
                2. 全是我自己建的圈时，管数恒等于总数（实测 5 = 5），纯冗余。
              留着等于同一件事说三遍，还占分组头的位置。
            -->
          </h2>
          <div class="circle-rows">
            <CircleRow
              v-for="c in myCircles"
              :key="`m-${c.id}`"
              :circle="c"
              @open="goDetail"
              @join="joinCircle"
            />
          </div>
        </section>

        <section class="circles__group">
          <h2 class="circles__group-head">
            <span class="circles__group-title">发现</span>
            <span class="circles__group-meta">按最近活跃</span>
            <!-- C15:换一批而不是翻页器。轮换窗口,按活跃顺序看下一组 -->
            <button
              v-if="canSwapDiscover"
              type="button"
              class="circles__group-swap"
              @click="swapDiscover"
            >
              换一批
            </button>
          </h2>
          <div v-if="discoverView.length" class="circle-rows">
            <CircleRow
              v-for="c in discoverView"
              :key="`d-${c.id}`"
              :circle="c"
              @open="goDetail"
              @join="joinCircle"
            />
          </div>
          <!-- C14:这个空态只在「圈子存在但我全加了」时出现,文案与事实一致 -->
          <div v-else class="circles__empty">
            <p class="circles__empty-t">都加过了</p>
            <p class="circles__empty-p">现有圈子你都在里面了，建一个新的吧</p>
            <button type="button" class="circles__empty-b" @click="onCreateClick">创建圈子</button>
          </div>
        </section>
      </template>
    </template>

    <!--
      尾部文案跟内容走(C2),不再无脑「到底了」:
      搜索态 -> 只找到 N 个;没拉完 -> 加载中;拉完 -> 说清是谁排完了。
      没有任何可翻页内容时整行不出现 —— 空页底下再挂一句「共 0 个圈」只是噪音;
      「全加入了」且已拉完时发现段没有可翻内容,也不该在「都加过了」下面报总数。
      hasMore / loading 必须留在条件里:首屏全是已加入的圈时发现段为空,但后面
      还有页 —— 这一行同时是无限滚动的观察哨,藏掉就再也翻不到下一页。
    -->
    <div
      v-if="isSearching ? results.length : discoverView.length || hasMore || loading"
      class="sentinel"
    >
      <span v-if="loading || searching" class="circles__more">加载中</span>
      <span v-else-if="isSearching && results.length" class="circles__more">
        只找到 {{ results.length }} 个
      </span>
      <span v-else-if="!hasMore && loaded" class="circles__more">
        {{ tailText }}
      </span>
    </div>

    <!-- 创建圈子:移动端全屏抽屉(固定宽弹窗在 375px 下横向溢出,红线) -->
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
            maxlength="7"
            placeholder="如 篮球社 / 考研圈 / 摄影社"
            @keyup.enter="create"
          />
          <p class="create__hint">3-7 字,需含汉字(可搭配字母/数字,如「23届网安」),不能纯数字/纯字母</p>
          <!-- 错误贴近字段,且输入的名字必须保留 -->
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
 * 圈子列表(Circles.vue.md)。
 *
 * 2026-10-21 与设计稿对齐:删掉手机端「三个门」入口视图,手机桌面一套
 * 「我的 / 发现」结构,形态差异(单列行 / 多列卡)全部在 CircleRow 的媒体查询里。
 */
import { computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, WarningFilled } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import CircleRow from '@/components/common/CircleRow.vue'
import { useCircles } from '@/composables/useCircles'
import { openAuthGate } from '@/composables/useAuthGate'

const router = useRouter()

const {
  circles, hasMore, loading, loadFailed, loaded, total,
  keyword, searching, searchFailed, results, isSearching,
  myCircles, discoverView, canSwapDiscover, swapDiscover,
  showCreate, newName, creating, createError,
  openCreate, load, runSearch, clearSearch, joinCircle, create,
} = useCircles()

/**
 * 尾部一句话(C2):数字用后端 total 而不是已加载条数(C16/F5),
 * 后端没给 total 时宁可不写数字也不写错的。
 */
const tailText = computed(() => {
  const base = '发现按最近活跃'
  if (total.value == null) return `${base}排完就是全部`
  return `${base}排完就是全部 · 共 ${total.value} 个圈`
})

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

/** AC-11:未认证时弹全站认证闸门,不直接开抽屉 */
function onCreateClick() {
  if (openCreate() === 'need-verify') openAuthGate()
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
