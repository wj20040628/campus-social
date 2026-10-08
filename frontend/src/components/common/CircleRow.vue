<template>
  <!--
    单个圈子行(设计 §1.1)。
    抽成组件的原因:同一份行结构在「我加入的」与「全部圈子」两个分组里各用一次,
    且移动端行式 / 桌面端卡片式两套形态的差异全部收在这个组件内,
    页面不需要为形态差异写分支。

    整行是<button> 而不是 div @click(设计 §7):
    el-card 渲染成 div,键盘用户 Tab 不到,读屏器也不知道它可点。
  -->
  <div class="crow">
    <!-- 整行可点区。加入按钮在下面用 @click.stop 截断冒泡 -->
    <button type="button" class="crow__open" @click="$emit('open', circle.id)">
      <img
        v-if="circle.avatarUrl"
        class="crow__avatar"
        :src="resolveUrl(circle.avatarUrl)"
        :alt="`${circle.name}的图标`"
      />
      <span v-else class="crow__avatar crow__avatar--blank" aria-hidden="true">
        <ChatDotRound />
      </span>

      <span class="crow__info">
        <span class="crow__name">{{ circle.name }}</span>
        <span class="crow__sub">{{ memberText }}</span>
      </span>
    </button>

    <button
      v-if="!circle.joined"
      type="button"
      class="crow__join"
      :aria-label="`加入 ${circle.name}`"
      @click.stop="$emit('join', circle)"
    >
      加入
    </button>
    <span v-else class="crow__joined">
      <!-- 图标 + 文字双通道:不能只靠底色差异表达「已加入」(设计 §5) -->
      <CircleCheck class="crow__joined-icon" aria-hidden="true" />
      已加入
    </span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CircleCheck, ChatDotRound } from '@element-plus/icons-vue'
import { resolveUrl } from '@/utils/resolve'

const props = defineProps({
  circle: { type: Object, required: true },
})

defineEmits(['open', 'join'])

/**
 * 成员数 999+ 封顶(设计 §3 内容超长)。
 * 不封顶的话一个万人圈子会把行撑宽,且「12345」没有信息量。
 */
const memberText = computed(() => {
  const n = Number(props.circle.memberCount || 0)
  const shown = n > 999 ? '999+' : String(n)
  const owner = props.circle.owner?.nickname
  return owner ? `${shown} 成员 · 圈主：${owner}` : `${shown} 成员`
})
</script>

<style scoped>
.crow {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  min-height: 72px;
  padding: var(--ys-space-3) var(--ys-gutter-phone);
  border-top: 1px solid var(--ys-border-soft);
}

.crow__open {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  flex: 1;
  /* 视觉高 72px 由 .crow 给,这里撑满让整行都是热区 */
  align-self: stretch;
  padding: 0;
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  text-align: left;
  cursor: pointer;
}

.crow__open:active {
  opacity: 0.7;
}

.crow__avatar {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: var(--ys-radius-lg);
  object-fit: cover;
}

.crow__avatar--blank {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ys-surface-2);
  color: var(--ys-muted);
  font-size: var(--ys-text-xl);
}

.crow__info {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-1);
  flex: 1;
  min-width: 0;
}

.crow__name {
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
  /* 圈子名 1 行截断(设计 §3) */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.crow__sub {
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 「加入」胶囊按钮:44px 高、最小宽 72px(设计 §1.1) */
.crow__join {
  flex-shrink: 0;
  min-width: 72px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: transparent;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-accent);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out);
}

.crow__join:hover {
  background: var(--ys-accent-soft);
}

.crow__join:active {
  opacity: 0.7;
}

.crow__join:disabled {
  border-color: var(--ys-border-control);
  color: var(--ys-meta);
  cursor: not-allowed;
}

.crow__joined {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  flex-shrink: 0;
  min-width: 72px;
  min-height: var(--ys-touch-min);
  justify-content: center;
  padding: 0 var(--ys-space-3);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.crow__joined-icon {
  width: 16px;
  height: 16px;
}
</style>