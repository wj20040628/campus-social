<template>
  <!--
    会话顶栏 + 来源上下文条(2026-10-15 从 ChatThread.vue 拆出)。
    2026-10-20:群聊把「群名 · 人数」并进这一行,右侧操作换成 ⋯ 菜单(#actions 插槽);
    单聊保持原来的搜索/通知两个按钮。改版前群名在顶栏和群信息头各出现一次,
    下面那行还挂着三个圆钮,垂直方向白吃掉 110px。
  -->
  <div class="th__bar" role="banner">
    <button
      type="button"
      class="th__bar-back"
      aria-label="返回上一页"
      @click="goBack"
    >
      <el-icon :size="22"><Back /></el-icon>
    </button>
    <div class="th__bar-mid">
      <span class="th__bar-title" :title="peerName">{{ peerName || '会话' }}</span>
      <span v-if="subtitle" class="th__bar-sub">{{ subtitle }}</span>
    </div>
    <div class="th__bar-actions">
      <!-- 群聊塞 ⋯ 菜单进来;不传插槽时退回单聊的搜索/通知 -->
      <slot name="actions">
        <button
          type="button"
          class="th__bar-act"
          aria-label="找人"
          @click="router.push('/search')"
        >
          <el-icon :size="20"><Search /></el-icon>
        </button>
        <button
          type="button"
          class="th__bar-act"
          aria-label="通知"
          @click="router.push('/notify')"
        >
          <el-icon :size="20"><Bell /></el-icon>
        </button>
      </slot>
    </div>
  </div>

  <!-- 来源上下文条。这是「不加好友也能聊」的由头,不可删 -->
  <div v-if="sourceTitle" class="src">
    <el-icon :size="16" class="src__pin"><Top /></el-icon>
    <span class="src__text">来自:{{ sourceTitle }}</span>
    <button type="button" class="src__close" aria-label="收起来源" @click="emit('close-source')">
      <el-icon :size="16"><Close /></el-icon>
    </button>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Top, Close, Back, Search, Bell } from '@element-plus/icons-vue'

defineProps({
  peerName: { type: String, default: '' },
  /** 标题右侧的次要信息,群聊传「12 人」,单聊留空 */
  subtitle: { type: String, default: '' },
  sourceTitle: { type: String, default: '' },
})

const emit = defineEmits(['close-source'])

const router = useRouter()

/** 手机端会话是独立路由,history.back 回列表页;桌面无列表页,back 回上一条 */
function goBack() {
  if (window.history.length > 1) router.back()
  else router.replace('/chat')
}
</script>

<style scoped>
.th__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
  min-height: var(--ys-topbar-h);
  padding: 0 var(--ys-space-2);
  background: var(--ys-surface);
  border-bottom: 1px solid var(--ys-border);
  z-index: var(--ys-z-sticky);
}
.th__bar-back {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  cursor: pointer;
  flex: none;
}
.th__bar-mid {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.th__bar-title {
  min-width: 0;
  font-size: var(--ys-text-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.th__bar-sub {
  flex: none;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}
.th__bar-actions {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  flex: none;
}
.th__bar-act {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  cursor: pointer;
  border-radius: var(--ys-radius-lg);
}

.src {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  min-height: 32px;
  padding: 0 var(--ys-space-2) 0 var(--ys-space-4);
  background: var(--ys-surface-2);
  flex: none;
}

.src__pin {
  flex: none;
  color: var(--ys-accent);
}

.src__text {
  flex: 1;
  font-size: var(--ys-text-xs);
  color: var(--ys-fg-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.src__close {
  flex: none;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-muted);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
}
</style>
