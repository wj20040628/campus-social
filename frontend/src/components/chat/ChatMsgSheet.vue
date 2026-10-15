<template>
  <!--
    长按气泡后的操作表(2026-10-15 从 ChatThread.vue 拆出)。
    抽出的理由:ChatThread.vue 436 行超 300 行门禁;且这是一个完整交互
    (撤回 / 引用回复 / 看主页),开合与「当前操作哪条消息」都由父组件持有,
    本组件只渲染三个动作并根据权限显隐 —— 与消息窗本身无关。
  -->
  <ActionSheet :model-value="modelValue" :title="title" @update:model-value="emit('update:modelValue', $event)">
    <button v-if="canRecall" type="button" class="sheet__act" @click="pick('recall')">
      <el-icon :size="18"><RefreshLeft /></el-icon>
      撤回这条
    </button>
    <button v-if="canQuote" type="button" class="sheet__act" @click="pick('quote')">
      <el-icon :size="18"><Paperclip /></el-icon>
      引用回复
    </button>
    <button type="button" class="sheet__act sheet__act--danger" @click="pick('go-peer')">
      <el-icon :size="18"><User /></el-icon>
      查看 TA 的主页
    </button>
  </ActionSheet>
</template>

<script setup>
import { RefreshLeft, Paperclip, User } from '@element-plus/icons-vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'

defineProps({
  /** 开合(v-model) */
  modelValue: { type: Boolean, default: false },
  /** 标题:群聊是「这条群消息」,单聊是「发给 XXX 的消息」 */
  title: { type: String, default: '' },
  /** 能否撤回:由父组件按消息时间与归属判定 */
  canRecall: { type: Boolean, default: false },
  /** 能否引用:对方发的、未撤回的消息才可引用 */
  canQuote: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'recall', 'quote', 'go-peer'])

/** 动作一律先关层再上报:父组件不必在每个 handler 里重复写关闭 */
function pick(kind) {
  emit('update:modelValue', false)
  emit(kind)
}
</script>

<style scoped>
/* 操作表样式随组件从 ChatThread.css 搬迁(原 489-503 行),未改一个值。
   注意:危险项用 --ys-accent-2 而不是 --ys-danger —— 项目里「退出/删除/移出」
   这类破坏性动作统一走暖橙(见 CircleRow 的退出按钮同款处理) */
.sheet__act {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  width: 100%;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border: none;
  background: transparent;
  color: var(--ys-fg);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  text-align: left;
  cursor: pointer;
}

.sheet__act--danger {
  color: var(--ys-accent-2);
}
</style>
