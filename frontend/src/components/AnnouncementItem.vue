<template>
  <!--
    公告条目(Announcement.vue.md §1.1 / §7)
    首页公告条与公告列表页共用本组件,避免两处视觉漂移。
    首页传 compact 变体:高 64px、带 Close、可当天收起。
  -->
  <component
    :is="to ? 'RouterLink' : 'button'"
    :to="to"
    class="ann-item"
    :class="{ 'ann-item--compact': compact, 'ann-item--top': item.isTop === 1 }"
    :aria-label="ariaLabel"
  >
    <div class="ann-item__head">
      <!-- 来源徽章:深墨底白字。权威感靠它,不靠彩色边框 -->
      <span class="ann-item__org">{{ orgName }}</span>
      <!-- 紧急分类:文字 + 浅底,不用彩色左边框 -->
      <span v-if="item.category === 5" class="ann-item__urgent">紧急</span>
      <el-icon v-if="item.isTop === 1" class="ann-item__pin" :size="16"><Top /></el-icon>
      <!-- 未读圆点 + 标题字重 500 双重区分,不靠底色(底色差异会变斑马纹) -->
      <span v-if="!item.isRead" class="ann-item__dot" aria-hidden="true" />
    </div>

    <p class="ann-item__title" :class="{ 'is-unread': !item.isRead }">{{ item.title }}</p>

    <p v-if="item.summary" class="ann-item__summary">{{ item.summary }}</p>

    <p class="ann-item__meta">
      <span class="ys-num">{{ publishText }}</span>
      <template v-if="showReadCount && item.readCount != null">
        <span class="ann-item__sep" aria-hidden="true">·</span>
        <span class="ys-num">已读 {{ cap(item.readCount) }} 人</span>
      </template>
    </p>
  </component>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { Top } from '@element-plus/icons-vue'

/**
 * @param {object} item  AnnouncementListItem(见 openapi.yaml)
 * @param {boolean} compact 首页紧凑形态
 * @param {boolean} to   传路径则为 RouterLink,否则渲染为 button
 * @param {boolean} showReadCount 仅管理员可见
 */
const props = defineProps({
  item: { type: Object, required: true },
  compact: { type: Boolean, default: false },
  to: { type: [String, Object], default: null },
  showReadCount: { type: Boolean, default: false },
})

/** 分类 1教务 2后勤 3活动 4社团 5紧急 6其他 */
const CATEGORY = {
  1: '教务',
  2: '后勤',
  3: '活动',
  4: '社团',
  5: '紧急',
  6: '其他',
}

/**
 * 来源机构名。优先取后端下发的 publisher.schoolName,
 * 截断到 6 字 —— 徽章宽度固定,超出会把标题挤掉。
 */
const orgName = computed(() => {
  const p = props.item.publisher
  const raw =
    p?.schoolName ||
    p?.nickname ||
    (props.item.category ? CATEGORY[props.item.category] : '') ||
    '学校通知'
  return raw.length > 6 ? `${raw.slice(0, 6)}…` : raw
})

const publishText = computed(() => {
  const t = props.item.publishTime
  if (!t) return '未发布'
  return String(t).slice(0, 16).replace('T', ' ')
})

/** 已读人数 9999+ 封顶 */
function cap(n) {
  return n > 9999 ? '9999+' : String(n)
}

/** 未读条目整条的读屏播报:不只靠红点传达未读 */
const ariaLabel = computed(() => {
  const parts = []
  if (!props.item.isRead) parts.push('未读公告')
  if (props.item.isTop === 1) parts.push('置顶公告')
  parts.push(props.item.title)
  parts.push(`来源 ${orgName.value}`)
  return parts.join('，')
})
</script>

<style scoped>
.ann-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: var(--ys-space-4);
  border: none;
  border-top: 1px solid var(--ys-border-soft);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  color: inherit;
  text-decoration: none;
  cursor: pointer;
  /* 最小高 72px,保证触控热区 */
  min-height: 72px;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.ann-item:hover {
  background: var(--ys-surface-2);
}

/* 首页紧凑态 */
.ann-item--compact {
  min-height: 64px;
  padding: var(--ys-space-3) var(--ys-space-4);
  border-radius: var(--ys-radius-xl);
  border-top: none;
  background: var(--ys-official-surface);
}

.ann-item__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-1);
}

.ann-item__org {
  font-size: var(--ys-text-xs);
  line-height: 1.5;
  padding: 1px var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  background: var(--ys-official-badge-bg);
  color: var(--ys-official-badge-text);
  max-width: 8em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ann-item__urgent {
  font-size: var(--ys-text-xs);
  line-height: 1.5;
  padding: 1px var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  background: var(--ys-danger-soft);
  color: var(--ys-danger);
}

.ann-item__pin {
  color: var(--ys-accent);
}

.ann-item__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-danger);
  margin-left: auto;
  flex: none;
}

.ann-item__title {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  color: var(--ys-fg-2);
  font-weight: var(--ys-weight-regular);
  /* 最多 2 行 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  /* 无空格长串不撑破容器(AC-15 横向滚动的头号成因) */
  overflow-wrap: anywhere;
}

.ann-item__title.is-unread {
  color: var(--ys-fg);
  font-weight: var(--ys-weight-medium);
}

.ann-item__summary {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ann-item--compact .ann-item__summary {
  display: none;
}

.ann-item__meta {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-meta);
}

.ann-item__sep {
  margin: 0 var(--ys-space-1);
}
</style>