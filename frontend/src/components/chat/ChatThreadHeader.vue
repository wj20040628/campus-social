<template>
  <!--
    会话顶栏 + 来源上下文条(2026-10-15 从 ChatThread.vue 拆出)。
    拆出的理由:ChatThread.vue 超 300 行门禁;且这两条都固定在消息窗最上方,
    只做「显示 + 导航」,完全不参与消息状态 —— 是最好拆的一块。
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
    <div class="th__bar-title" :title="peerName">{{ peerName || '会话' }}</div>
    <div class="th__bar-actions">
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
}
.th__bar-title {
  flex: 1;
  min-width: 0;
  font-size: var(--ys-text-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

/* 与 .reply__cancel 原是同一条共享规则(尺寸/手感一致);拆开后各留一份,
   避免为了共用而把两个组件再绑回一个文件 */
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
