<template>
  <!--
    「谁还没看」面板(AdminAnnouncement.vue.md §1.2,AC-10)
    从底部升起的 ActionSheet,高度 70vh。

    三条不能省的判断:
    1) 头部写「还有 914 人没看」而不是「914 人没看」——
       管理员关心绝对数字,但这个面板装不下 914 条,标题必须承认这一点。
    2) 名单**不做入场动画**(§4):914 人名单逐条淡入是性能灾难,
       而且滚动时补间动画会持续占用主线程。
    3) 面板内**不做批量勾选**(§1.2):手机上的批量选择是误触之源。
  -->
  <ActionSheet
    :model-value="modelValue"
    :title="headTitle"
    height="70dvh"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <template #header>
      <button
        type="button"
        class="remind-all"
        :disabled="reminding || total === 0"
        :aria-describedby="totalDescId"
        :aria-label="`一键提醒还有 ${total} 人没看`"
        @click="$emit('remind-all')"
      >
        {{ reminding ? '提醒中' : '一键提醒' }}
      </button>
    </template>

    <p :id="totalDescId" class="sr-only">这条公告还有 {{ total }} 人没看</p>

    <!-- 搜索行:按学院 / 昵称。前端过滤,名单只有 50 条不需要服务端搜索 -->
    <div class="search">
      <el-input v-model="keyword" placeholder="按昵称或学院搜" clearable :prefix-icon="Search" />
    </div>

    <SkeletonList v-if="loading" :count="8" variant="row" />

    <div v-else-if="failed" class="panel-err">
      <p class="panel-err__text">名单没拉出来</p>
      <button type="button" class="panel-err__btn" @click="$emit('retry')">重试</button>
    </div>

    <!-- 全部已读:不出空态图片,一行文字说清楚(§3 空态 3) -->
    <p v-else-if="total === 0" class="panel-note">这条公告所有人都读过了</p>

    <p v-else-if="filtered.length === 0" class="panel-note">
      没找到叫「{{ keyword }}」的人,换个字试试
    </p>

    <ul v-else class="list">
      <li v-for="u in filtered" :key="u.id" class="row">
        <span class="row__avatar" aria-hidden="true">{{ u.nickname.slice(0, 1) }}</span>
        <span class="row__info">
          <span class="row__name">{{ u.nickname }}</span>
          <span class="row__meta">{{ u.dept || '未填学院' }}</span>
        </span>
        <button
          type="button"
          class="row__act"
          :class="{ 'is-done': reminded.has(u.id) }"
          :disabled="reminded.has(u.id)"
          :aria-label="`提醒 ${u.nickname}`"
          title="单人提醒暂未开放，用右上角一键提醒"
          @click="$emit('remind-one', u.id)"
        >
          {{ reminded.has(u.id) ? '已提醒' : '提醒' }}
        </button>
      </li>
    </ul>
  </ActionSheet>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Search } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  list: { type: Array, default: () => [] },
  total: { type: Number, default: 0 },
  loading: { type: Boolean, default: false },
  failed: { type: Boolean, default: false },
  reminding: { type: Boolean, default: false },
  /** 已提醒过的用户 id 集合(Set 不能直接传给 prop,父组件传数组) */
  remindedIds: { type: Array, default: () => [] },
})

defineEmits(['update:modelValue', 'remind-all', 'remind-one', 'retry'])

const keyword = ref('')

const reminded = computed(() => new Set(props.remindedIds))

/** aria-describedby 指向的隐藏说明节点 id */
const totalDescId = 'unread-panel-total'

const headTitle = computed(() =>
  props.total === 0 ? '全部都看过了' : `还有 ${props.total} 人没看`,
)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return props.list
  return props.list.filter(
    (u) => u.nickname?.toLowerCase().includes(k) || u.dept?.toLowerCase().includes(k),
  )
})

// 每次打开重置搜索词:上次搜的人名不该在这次打开时还留着。
// 用 immediate 的 watch 而不是 onMounted —— 组件是常驻的,面板才是开关。
watch(
  () => props.modelValue,
  (open) => {
    if (open) keyword.value = ''
  },
)
</script>

<style scoped>
.search {
  padding: var(--ys-space-3) var(--ys-space-4);
  border-bottom: 1px solid var(--ys-border-soft);
  position: sticky;
  top: 0;
  background: var(--ys-surface);
  z-index: var(--ys-z-sticky);
}

.remind-all {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border-radius: var(--ys-radius-full);
  border: 1px solid var(--ys-accent);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  /* 宽度锁定:loading 时按钮文字变 3 字,不能把「一键提醒」挤变形 */
  min-width: 88px;
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
}

.remind-all:disabled {
  background: var(--ys-surface-2);
  border-color: var(--ys-border);
  color: var(--ys-meta);
  cursor: not-allowed;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.panel-note {
  margin: 0;
  padding: var(--ys-space-6) var(--ys-space-4);
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
  text-align: center;
}

.panel-err {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-8) var(--ys-space-4);
}

.panel-err__text {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

.panel-err__btn {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-5);
  border-radius: var(--ys-radius-lg);
  border: 1px solid var(--ys-border-control);
  background: var(--ys-surface);
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  cursor: pointer;
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  min-height: 56px;
  padding: 0 var(--ys-space-4);
}

.row + .row {
  border-top: 1px solid var(--ys-border-soft);
}

.row__avatar {
  flex: none;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
  font-size: var(--ys-text-base);
}

.row__info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.row__name {
  font-size: var(--ys-text-base);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row__meta {
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.row__act {
  flex: none;
  min-width: 56px;
  min-height: var(--ys-touch-min);
  border: none;
  background: transparent;
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}

.row__act.is-done {
  color: var(--ys-meta);
  cursor: not-allowed;
}
</style>
