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

    <!-- 行 3:时间 + 已读/已发 -->
    <p class="item__meta ys-num">
      {{ timeText }}
      <span class="item__dot">·</span>
      已读 {{ cap(item.readCount) }} / 已发 {{ cap(item.sendCount) }}
    </p>

    <!-- 行 4:操作行,4 个文字按钮横排,各 44x44 热区 -->
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

.item__ops {
  display: flex;
  gap: var(--ys-space-4);
  margin-top: var(--ys-space-2);
}

.op {
  min-height: var(--ys-touch-min);
  padding: 0;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  transition: opacity var(--ys-motion-instant) var(--ys-ease-out);
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
</style>
