<template>
  <!--
    举报「某条聊天消息」（2026-10-21 新增）。

    【为什么单独抽组件】
    1. ChatThread.vue 已经 421 行，超了 400 行门禁；这块是自成一体的浮层，
       抽出来是自然的切分点。
    2. 举报表本身不需要知道聊天上下文（会话 id、对方是谁），只需要消息 id。
       抽出来之后，将来群聊/单聊若要分别定制文案，改这一处就够。

    后端能力：POST /report 的 targetType=4（聊天消息）。
    举报成立时 ReportServiceImpl 会把该消息 is_deleted=1，
    于是它从双方会话里消失 —— 这正是「举报骚扰私信」应有的效果。
  -->
  <ReportSheet
    v-model="open"
    :target-type="TARGET_TYPE"
    :target-id="messageId"
    sheet-title="举报这条消息"
    :submit-fn="submit"
  />
</template>

<script setup>
import { computed } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { submitReport } from '@/api/review'
import ReportSheet from '@/components/post/ReportSheet.vue'

/** targetType=4 = 聊天消息，与 openapi 契约的 enum 一致 */
const TARGET_TYPE = 4

const props = defineProps({
  /** 开合（v-model） */
  modelValue: { type: Boolean, default: false },
  /** 被举报消息 id */
  messageId: { type: [Number, String], default: null },
})

const emit = defineEmits(['update:modelValue'])

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/**
 * 提交。复用既有幂等端点。
 *
 * 「已受理 / 已经举报过」的文案由 ReportSheet 内部按 duplicated 区分，
 * 这里只把接口调用透传下去 —— 不再重复弹 toast，否则同一动作会提示两次。
 */
function submit(payload) {
  return submitReport(payload)
}
</script>
