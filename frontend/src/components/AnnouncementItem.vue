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
    :class="{
      'ann-item--compact': compact,
      'ann-item--top': item.isTop === 1,
      'ann-item--unread': !item.isRead,
    }"
    :aria-label="ariaLabel"
  >
    <div class="ann-item__head">
      <!--
        来源徽章已移除（2026-10-21）。
        原来这里渲染 orgName，已认证发布人的公告会显示「黄河交通学院」——
        而本平台是毕业设计原型，与学校没有隶属关系（见 legalDocs 里的免责声明），
        公告上挂具体校名等于替学校背书。现在公告条目只保留「分类 / 紧急」两个
        客观标签，不再出现任何发布方身份信息。
      -->
      <span v-if="item.category" class="ann-item__cat">{{ categoryName }}</span>
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
import { relTime } from '@/utils/relTime'

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
 * 分类名（2026-10-21）。
 *
 * 取代原来的 orgName：那个取的是 publisher.schoolName / nickname，
 * 已认证发布人的公告会显示「黄河交通学院」这类具体校名。
 * 本平台与学校无隶属关系（legalDocs 里有明确免责声明），
 * 公告不该挂具体校名 —— 所以这里只显示**客观的分类**，不含任何发布方身份。
 */
const categoryName = computed(() => CATEGORY[props.item.category] || '其他')

/**
 * 发布时间（2026-10-21 改相对时间）。
 *
 * 改版前是 `String(t).slice(0,16).replace('T',' ')` —— 直接把后端 ISO 串
 * 截成 "2026-10-12 15:38" 这种机器格式。公告要回答的是「新不新」，
 * 而这个格式要求用户自己在脑子里做减法。
 *
 * 现在复用全站同一份 relTime（与首页卡片、圈子列表一致）：
 * 1 小时内「N 分钟前」、当天「N 小时前」、7 天内「N 天前」，
 * 更早退化成「10 月 12 日」。
 */
const publishText = computed(() => {
  const t = props.item.publishTime
  if (!t) return '未发布'
  // relTime 认 "yyyy-MM-dd HH:mm" 与 ISO 两种形态；Safari 不认带空格的，
  // relTime 内部已做 replace(' ', 'T') 处理
  const rel = relTime(t)
  return rel || String(t).slice(0, 16).replace('T', ' ')
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
  // 播报分类而不播报发布方（2026-10-21：公告不再挂具体校名/发布人身份）
  parts.push(categoryName.value)
  parts.push(props.item.title)
  return parts.join('，')
})
</script>

<style scoped>
.ann-item {
  display: block;
  /*
   * width:100% + min-width:0（2026-10-21）：条目是 <a>/<button>，
   * 默认按内容宽度走。列表里出现超长标题或无空格长串时会把自身顶宽，
   * 进而顶破 .ann-page 的容器（桌面端实测 672 > 642）。
   * min-width:0 是让它在 grid/flex 父级里允许被压缩到容器宽度以内。
   */
  width: 100%;
  min-width: 0;
  text-align: left;
  padding: var(--ys-space-4);
  box-sizing: border-box;
  border: none;
  border-top: 1px solid var(--ys-border-soft);
  /*
   * 底色按「已读」给：极浅的暖纸色 #fdfaf4。
   *
   * 原设计刻意「不用底色区分未读/已读」，怕列表变斑马纹。那个顾虑针对的是
   * 给未读项打高亮底（确实会花）。反过来压低已读项是安全的：
   *   - 未读项在下面覆盖回纯白 → 自然凸显
   *   - 相邻两个未读项之间仍是白的 → 不会出现一道浅一道深的斑马纹
   * 底色只降低「已读内容」的视觉重量，不做任何强调。
   */
  background: #fdfaf4;
  font-family: var(--ys-font-body);
  color: inherit;
  text-decoration: none;
  cursor: pointer;
  /* 最小高 72px,保证触控热区 */
  min-height: 72px;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

/* 未读项：纯白 + 右侧红点，是列表里唯一「亮」的 */
.ann-item--unread {
  background: var(--ys-surface);
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

/*
 * 分类标签（2026-10-21）。
 *
 * 取代原来的 .ann-item__org 深墨徽章：那个徽章装的是发布方（校名/昵称），
 * 现在公告不显示发布方，只显示客观分类，所以改成轻量的灰底标签 ——
 * 分类是「这条公告讲什么事」，不该有权威徽章的分量。
 */
.ann-item__cat {
  font-size: var(--ys-text-xs);
  line-height: 1.5;
  padding: 1px var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
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

/*
 * 标题与摘要的层级（2026-10-21 重调）。
 *
 * 改版前标题 14px / 摘要 13px，只差 1px —— 扫列表时两者糊成一片，
 * 「一眼扫标题」这个动作做不了。现在：
 *   标题 14.5px，未读 600 / 已读 400
 *   摘要 12.5px + 1 行截断（原来继承父级行高，可以撑两行）
 * 字号差 2px + 字重差 + 颜色差（fg vs muted）三档一起拉开。
 */
.ann-item__title {
  margin: 0;
  font-size: 14.5px;
  line-height: 1.55;
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
  word-break: break-word;
}

.ann-item__title.is-unread {
  color: var(--ys-fg);
  font-weight: var(--ys-weight-semibold);
}

.ann-item__summary {
  margin: var(--ys-space-1) 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ys-muted);
  /* 摘要固定 1 行：它只是「要不要点进去」的提示，给两行会盖过标题 */
  display: -webkit-box;
  -webkit-line-clamp: 1;
  line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
  overflow-wrap: anywhere;
  word-break: break-word;
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