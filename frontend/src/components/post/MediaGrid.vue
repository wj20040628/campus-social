<template>
  <!--
    发帖的媒体格子(Publish.vue.md §1 行 3/§3 错误态)
    抽成组件的原因:「上传中 / 上传失败 / 已完成」三态各有独立视觉与交互,
    塞进 Publish.vue 会把 270 行顶到门禁之上,而且这四块格子是重复结构。

    关键决策:**上传失败在格子里显示,不是一个全局 toast**。
    一次选 9 张时 toast 只说「上传失败」,用户不知道是哪张,
    也不知道该不该重传 —— 只能全部删掉重选(Publish.vue.md §3 明确要求)。
  -->
  <ul class="mg">
    <li
      v-for="(cell, i) in cells"
      :key="cell.key"
      class="mg__cell"
      :class="{ 'mg__cell--failed': cell.state === 'failed' }"
      :aria-busy="cell.state === 'uploading'"
    >
      <!-- 已完成:预览 + 右上角删除 -->
      <template v-if="cell.state === 'done'">
        <el-image
          v-if="cell.kind === 'image'"
          :src="resolveThumb(cell.url)"
          fit="cover"
          class="mg__thumb"
          :alt="`待发布的图片 ${i + 1},点右上角删除`"
        />
        <PostVideo v-else :src="cell.url" :poster="cell.poster" class="mg__thumb" />
        <span v-if="cell.kind === 'video'" class="mg__badge">视频</span>

        <button
          type="button"
          class="mg__remove"
          :aria-label="`删除第 ${i + 1} 张媒体`"
          @click="$emit('remove', i)"
        >
          <el-icon :size="16"><Close /></el-icon>
        </button>
      </template>

      <!--
        上传中(2026-10-29 改进):百分比 + 进度条 + 约剩时间。
        弱网传 20MB 视频要 1 分钟,只有转圈用户会以为卡死、提前退出。
      -->
      <template v-else-if="cell.state === 'uploading'">
        <div class="mg__progress">
          <span class="mg__pct ys-num">{{ cell.progress }}%</span>
          <span class="mg__bar" aria-hidden="true">
            <i :style="{ width: cell.progress + '%' }" />
          </span>
          <span class="mg__hint">{{ uploadHint(cell) }}</span>
        </div>
      </template>

      <!-- 上传失败:格内红底 + 重传。用户能精确知道是哪一张出的问题 -->
      <template v-else>
        <el-icon :size="16" class="mg__fail-icon"><WarningFilled /></el-icon>
        <span class="mg__hint mg__hint--danger">上传失败</span>
        <button
          type="button"
          class="mg__retry"
          :aria-label="`重传第 ${i + 1} 张媒体`"
          @click="$emit('retry', i)"
        >
          重传
        </button>
      </template>
    </li>
  </ul>
</template>

<script setup>
import { Close, WarningFilled } from '@element-plus/icons-vue'
import { resolveThumb } from '@/utils/resolve'
import PostVideo from '@/components/post/PostVideo.vue'

/**
 * @param {Array<{key:string, state:'uploading'|'done'|'failed', kind?:'image'|'video', url?:string, file?:File}>} cells
 *        已上传成功的格子也要传进来 —— 父组件持有唯一真源(media),
 *        这里只做展示,不做状态的增删。
 */
defineProps({
  cells: { type: Array, default: () => [] },
})

defineEmits(['remove', 'retry'])

/** 上传中格子底部的说明:不确定大小/速度时只说要等,有速度后给剩余时间 */
function uploadHint(cell) {
  if (cell.progress >= 99) return '处理中…'
  if (cell.etaSec) {
    return cell.etaSec < 60
      ? `约剩 ${Math.ceil(cell.etaSec)} 秒`
      : `约剩 ${Math.ceil(cell.etaSec / 60)} 分钟`
  }
  return cell.kind === 'video' ? '视频较大，请稍候' : '上传中'
}
</script>

<style scoped>
.mg {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ys-space-2);
  margin: 0;
  padding: 0 var(--ys-space-4);
  list-style: none;
}

.mg__cell {
  position: relative;
  aspect-ratio: 1;
  min-width: 80px;
  overflow: hidden;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
}

.mg__thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.mg__badge {
  position: absolute;
  left: var(--ys-space-1);
  bottom: var(--ys-space-1);
  padding: 0 var(--ys-space-1);
  border-radius: var(--ys-radius-sm);
  background: var(--ys-overlay);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: #fff;
}

/* 删除按钮视觉 24px,热区靠 padding 撑到 44px(Publish.vue.md §5) */
.mg__remove {
  position: absolute;
  top: 0;
  right: 0;
  display: grid;
  place-items: center;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  padding: 10px 10px 14px 14px;
  border: 0;
  background: none;
  color: #fff;
  cursor: pointer;
}

.mg__remove::before {
  content: '';
  position: absolute;
  top: 2px;
  right: 2px;
  width: 24px;
  height: 24px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-overlay);
  z-index: -1;
}

.mg__progress {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: var(--ys-space-2);
}

.mg__pct {
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  font-weight: 600;
  color: var(--ys-text);
}

.mg__bar {
  width: 72%;
  height: 3px;
  overflow: hidden;
  border-radius: var(--ys-radius-full);
  background: var(--ys-border);
}

.mg__bar > i {
  display: block;
  height: 100%;
  background: var(--ys-accent);
  transition: width 0.2s linear;
}

.mg__progress .mg__hint {
  margin-top: 0;
}

.mg__cell--failed {
  background: var(--ys-danger-soft);
}

.mg__fail-icon {
  color: var(--ys-danger);
}

.mg__hint {
  display: block;
  margin-top: var(--ys-space-1);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-meta);
  text-align: center;
}

.mg__hint--danger {
  color: var(--ys-danger);
}

.mg__retry {
  display: block;
  margin: var(--ys-space-1) auto 0;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  color: var(--ys-danger);
  text-decoration: underline;
  cursor: pointer;
}

@media (min-width: 1024px) {
  .mg {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (prefers-reduced-motion: reduce) {
  .mg__bar > i {
    transition: none;
  }
}
</style>