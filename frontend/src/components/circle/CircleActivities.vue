<template>
  <div class="cd__body">
    <div class="cd__act-bar">
      <!-- 权限不足:按钮不渲染,换一行说明(§3)。禁止「点了才弹没权限」 -->
      <button v-if="canCreate" type="button" class="cd__act-new" @click="$emit('new')">
        发布活动
      </button>
      <span v-else class="cd__act-hint">加入圈子后就能发活动了</span>
    </div>

    <ul class="cd__acts">
      <li v-for="a in activities" :key="a.id" class="act">
        <button type="button" class="act__btn" @click="$emit('open', a)">
          <span class="act__row">
            <span class="act__title">{{ a.title }}</span>
            <!--
              状态标签:文字 + 颜色双通道。
              **不用 el-tag 的 success/warning/danger** —— 活动状态不是
              成功/警告/错误语义(CircleDetail.vue.md §1.2)。
            -->
            <span class="act__status" :class="`act__status--${tone(a.status)}`">
              {{ text(a.status) }}
            </span>
          </span>
          <span class="act__row">
            <el-icon :size="16"><Clock /></el-icon>
            <span class="act__meta ys-num">{{ timeText(a) }}</span>
          </span>
          <span class="act__row">
            <el-icon :size="16"><User /></el-icon>
            <span class="act__meta ys-num">{{ quota(a) }}</span>
          </span>
        </button>
      </li>
    </ul>

    <!-- 空态:必须带可点行动按钮(AC-19) -->
    <StateBlock
      v-if="!loading && activities.length === 0"
      variant="empty"
      title="还没有活动"
      :description="canCreate ? '凑几个人,定个时间就行' : '加入圈子看看别人都在约什么'"
      :action-label="canCreate ? '发布活动' : '加入圈子'"
      :icon="Calendar"
      @action="$emit('action')"
    />
    <SkeletonList v-if="loading" :count="3" variant="row" />
  </div>
</template>

<script setup>
/**
 * 圈子详情 - 活动 Tab(从 CircleDetail.vue 抽出)。
 * 抽出来的原因:这一块自带 4 个纯展示格式化函数 + 完整空态/加载态,
 * 和帖子 Tab 没有共享状态,内联在父组件里只会把文件推过 300 行门禁。
 */
import dayjs from 'dayjs'
import { Clock, User, Calendar } from '@element-plus/icons-vue'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'

defineProps({
  activities: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  canCreate: { type: Boolean, default: false },
})
defineEmits(['new', 'open', 'action'])

/* ---------------- 展示格式化(与后端 status 枚举解耦) ---------------- */

/** 状态文案。索引 0 起:招募中/ 名额已满 / 进行中 / 已结束 / 已取消 */
function text(s) {
  return ['招募中', '名额已满', '进行中', '已结束', '已取消'][s] || '状态待定'
}

/** 状态色 class。只三档,不套用 success/warning/danger 的错误语义 */
function tone(s) {
  if (s === 0) return 'open'
  if (s === 1) return 'full'
  return 'closed'
}

/** 时间区间。缺 startTime 是数据不全,明说「时间待定」而不是显示 Invalid Date */
function timeText(a) {
  if (!a.startTime) return '时间待定'
  const s = dayjs(a.startTime).format('MM-DD HH:mm')
  if (!a.endTime) return s
  return `${s} ~ ${dayjs(a.endTime).format('MM-DD HH:mm')}`
}

/** capacity 为 0 是「不限」,不是「一个名额都没有」 */
function quota(a) {
  return a.capacity === 0 ? '名额不限' : `名额 ${a.currentCount || 0} / ${a.capacity}`
}
</script>

<style scoped src="./CircleActivities.css"></style>