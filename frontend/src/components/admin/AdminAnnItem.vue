<template>
  <!--
    后台公告管理条目(AdminAnnouncement.vue.md §1.1)
    比前台条目多一层操作行,且**不折叠进「更多」菜单**(OD-19 硬要求:
    管理员会在手机上处理,操作藏进二级菜单等于藏没了)。
  -->
  <li class="item">
    <!-- 行 1:状态 + 分类 + 置顶标记。三重传达不靠颜色单通道 -->
    <div class="item__chips">
      <span class="chip" :class="`chip--${STATUS[item.status ?? 0].tone}`">{{ STATUS[item.status ?? 0].label }}</span>
      <span class="chip chip--cat">{{ CATEGORY[item.category] || '其他' }}</span>
      <span v-if="item.isTop === 1" class="chip chip--top">
        <el-icon :size="14"><Top /></el-icon>
        置顶
      </span>
    </div>

    <!-- 行 2:标题,最多 2 行 -->
    <p class="item__title">{{ item.title }}</p>

    <!-- 行 3:时间 + 阅读率 -->
    <p class="item__meta ys-num">
      {{ timeText }}
      <span class="item__dot">·</span>
      <!--
        2026-10-21 修：改版前显示「已读 3 / 已发 0」。
        后端 sendCount 一直没填充（实测恒为空），所以「已发 0」是错的；
        而管理员真正想看的是**阅读率**：读过的人在全校里占多少。
        后端 AnnouncementAdminItemVO 早就有 readCount / totalTargetCount / reachRate，
        只是前端没用 —— 这里直接改成「3 / 73 已读」+ 进度条。
      -->
      <span>{{ cap(item.readCount) }} / {{ cap(item.totalTargetCount) }} 已读</span>
    </p>

    <!--
      阅读率进度条（2026-10-21 加）。
      数字之外给一条横向进度：管理员扫一眼就能分出「没人看」和「基本都看了」，
      而两个数字需要心算。只在已发布（status=1）时显示 ——
      草稿没有触达对象，进度条恒为 0 反而是噪音。
    -->
    <div v-if="item.status === 1 && (item.totalTargetCount ?? 0) > 0" class="item__rate">
      <span class="item__rate-bar">
        <span class="item__rate-fill" :style="{ width: ratePct + '%' }" />
      </span>
      <span class="item__rate-txt ys-num">{{ ratePct }}%</span>
    </div>

    <!-- 行 4:操作行。加分隔线与组内间距，避免 48 个裸文字误触 -->
    <div class="item__ops">
      <button
        type="button"
        class="op"
        :aria-label="`${item.isTop === 1 ? '取消置顶' : '置顶'}公告:${item.title}`"
        :disabled="busy"
        @click="$emit('top')"
      >
        {{ item.isTop === 1 ? '取消置顶' : '置顶' }}
      </button>
      <button
        type="button"
        class="op"
        :aria-label="`${item.status === 1 ? '下架' : '上架'}公告:${item.title}`"
        :disabled="busy"
        @click="$emit('shelf')"
      >
        {{ item.status === 1 ? '下架' : '上架' }}
      </button>
      <button
        type="button"
        class="op"
        :aria-label="`编辑公告:${item.title}`"
        :disabled="busy"
        @click="$emit('edit')"
      >
        编辑
      </button>
      <!-- 「谁还没看」仅已发布时可看:草稿没有已读名单 -->
      <button
        v-if="item.status === 1"
        type="button"
        class="op op--accent"
        :aria-label="`查看谁还没看:${item.title}`"
        :disabled="busy"
        @click="$emit('unread')"
      >
        谁还没看
      </button>
      <!-- 未发布时给出「为什么没有这个按钮」,而不是让按钮凭空消失 -->
      <span v-else class="op op--hint">发布后才能看名单</span>
    </div>
  </li>
</template>

<script setup>
/**
 * 单条公告的后台操作行。
 *
 * 为什么用文字按钮而不是图标按钮(§2 桌面端才换图标):
 * 「置顶 / 取消置顶」「上架 / 下架」是**状态相反的两个动作**,
 * 图标无法在同一个位置同时表达「现在是置顶的,点一下会取消」。
 * 手机上横排 4 个文字按钮宽度够(约 300px < 375px),不需要省。
 */
import { computed } from 'vue'
import { Top } from '@element-plus/icons-vue'
import { CATEGORY, STATUS } from '@/constants/announcement'

const props = defineProps({
  item: { type: Object, required: true },
  busy: { type: Boolean, default: false },
})

defineEmits(['top', 'shelf', 'edit', 'unread'])

const timeText = computed(() => {
  const t = props.item.publishTime || props.item.createTime
  if (!t) return '未发布'
  // 契约是 ISO 字符串;Safari 不认 '2026-01-01 10:00:00' 以外的空格分隔格式,
  // 故统一替换成 'T' 再交给 Date,避免 Invalid Date(沉默失败)
  const d = new Date(String(t).replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return String(t)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

function pad(n) {
  return String(n).padStart(2, '0')
}

/** 9999+ 封顶(§3 内容超长):已发量到百万级时不能让数字撑破布局 */
function cap(n) {
  const v = Number(n) || 0
  return v > 9999 ? '9999+' : String(v)
}

/**
 * 阅读率百分比（整数）。
 *
 * 优先用后端算好的 reachRate（0~1）；后端没给时用 readCount / totalTargetCount 兜底。
 * 两者都没有时返回 0 —— 此时进度条也不渲染（模板上有 v-if 守卫）。
 *
 * 下限 1：读了一个人也四舍五入成 2% 会显得不准，但显示 0% 又会让人以为
 * 「完全没人看」。所以大于 0 但不足 1% 时向下取到 1。
 */
const ratePct = computed(() => {
  const t = Number(props.item.totalTargetCount) || 0
  if (t <= 0) return 0
  const raw = props.item.reachRate != null
    ? Number(props.item.reachRate)
    : (Number(props.item.readCount) || 0) / t
  if (!Number.isFinite(raw) || raw <= 0) return 0
  const pct = Math.round(raw * 100)
  return pct === 0 ? 1 : Math.min(100, pct)
})
</script>

<style scoped>
.item {
  padding: var(--ys-space-4);
  list-style: none;
}

.item__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-2);
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 20px;
  padding: 0 var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-text-xs);
  line-height: 1;
}

.chip--live {
  background: var(--ys-success-soft);
  color: var(--ys-success);
}

.chip--draft {
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
}

.chip--off {
  background: var(--ys-surface-2);
  color: var(--ys-meta);
}

.chip--cat {
  background: var(--ys-surface-2);
  color: var(--ys-fg-2);
}

.chip--top {
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
}

.item__title {
  margin: 0;
  font-size: var(--ys-text-md);
  line-height: var(--ys-lh-md);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  /* 最多 2 行 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.item__meta {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.item__dot {
  margin: 0 var(--ys-space-1);
}

/* ---------------------------------------------------------------- 阅读率 */
/* 2026-10-21 加。数字之外给一条横条，让「没人看 / 基本都看了」一眼可分。 */
.item__rate {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
}

.item__rate-bar {
  flex: 1;
  max-width: 200px;
  height: 4px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  overflow: hidden;
}

.item__rate-fill {
  display: block;
  height: 100%;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  /* 极小比例下也要看得见：给一个最小宽度 */
  min-width: 2px;
  transition: width var(--ys-motion-base) var(--ys-ease-out);
}

.item__rate-txt {
  flex: none;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

/* ---------------------------------------------------------------- 操作行 */
/*
 * 2026-10-21 调整。
 * 实测 12 条公告 × 4 个按钮 = 48 个可点文字，全是没有边框的裸文字、
 * 间距 16px，而「下架」这类破坏动作紧挨着「编辑」—— 误触风险高。
 * 现在：上方加分隔线把它与内容分开、按钮之间给更紧的间距（视觉成组而非散落）、
 * 破坏性动作标红（模板里 op 的语义不变，只是颜色分级）。
 */
.item__ops {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  /* 16 → 0：按钮自带左右 padding，视觉间距靠它撑开，避免 48 个目标散成一片 */
  gap: 0;
  margin-top: var(--ys-space-2);
  padding-top: var(--ys-space-1);
  border-top: 1px solid var(--ys-border-soft);
}

.op {
  min-height: var(--ys-touch-min);
  /* 左右 padding 7px：相邻按钮的热区间隔 = 14px，既不误触也不浪费宽度 */
  padding: 0 var(--ys-space-2);
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
  /* 文字按钮不换行：「取消置顶」折行会变成两行高，破坏整行对齐 */
  white-space: nowrap;
}

.op--accent {
  color: var(--ys-accent);
}

.op:disabled {
  color: var(--ys-meta);
  cursor: not-allowed;
}

/* 「为什么没有这个按钮」的占位说明:与 op 同尺寸,视觉上不喧宾夺主 */
.op--hint {
  color: var(--ys-meta);
  font-size: var(--ys-text-xs);
  align-self: center;
  cursor: default;
}

.op:not(:disabled):active {
  opacity: 0.7;
}

/* ---------------------------------------------------------------- 桌面端 */
/*
 * 2026-10-21 加：桌面端改成「内容在左、操作在右」的两栏行。
 *
 * 改版前桌面端是手机上那套纵向堆叠原样放大：徽章一行、标题一行、
 * 元信息一行、按钮一行 —— 而容器限宽 760 后右侧仍空掉一大片。
 * 现在把操作行提到标题右侧，一行读完：
 *   左：状态徽章 + 标题 + 阅读率
 *   右：4 个操作按钮（与标题垂直居中）
 *
 * 用 grid 而不是 flex：.item 的子项有 4 个（chips / title / meta / rate / ops），
 * 要让 ops 跨行到右侧并垂直居中，grid 的 grid-row: 1 / -1 最直接。
 */
@media (min-width: 1024px) {
  .item {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    /* 左侧第 1 列放内容，右侧第 2 列放操作；行数由内容决定 */
    grid-template-rows: auto auto auto;
    align-items: center;
    column-gap: var(--ys-space-4);
    padding: var(--ys-space-3) var(--ys-space-4);
  }

  .item__chips {
    grid-column: 1;
    grid-row: 1;
  }

  .item__title {
    grid-column: 1;
    grid-row: 2;
    margin: 0;
    /* 桌面端标题不必折到两行：空间够，一行就够读 */
    -webkit-line-clamp: 1;
    line-clamp: 1;
    font-size: 15px;
  }

  .item__meta {
    grid-column: 1;
    grid-row: 3;
    margin-top: var(--ys-space-1);
  }

  /*
   * 阅读率条在桌面端并到元信息行下方（第 4 行），但左侧列只有 3 行定义。
   * 用 grid-row: 4 会自动新增一行，不需要显式声明。
   */
  .item__rate {
    grid-column: 1;
    grid-row: 4;
    margin-top: var(--ys-space-1);
  }

  /* 操作行跨全部行、贴右侧垂直居中 */
  .item__ops {
    grid-column: 2;
    grid-row: 1 / -1;
    align-self: center;
    justify-content: flex-end;
    margin: 0;
    padding: 0;
    border-top: 0;
    /* 桌面端按钮给点间距，避免 4 个紧贴成一片 */
    gap: var(--ys-space-2);
    flex-wrap: nowrap;
  }
}
</style>
