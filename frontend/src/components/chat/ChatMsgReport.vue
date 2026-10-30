<template>
  <!--
    举报「某条聊天消息」（2026-10-21 新增）。

    【为什么单独抽组件】
    1. ChatThread.vue 已经 421 行，超了 400 行门禁；这块是自成一体的浮层，
       抽出来是自然的切分点。
    2. 举报表本身不需要知道聊天上下文（会话 id、对方是谁），只需要消息 id。
       抽出来之后，将来群聊/单聊若要分别定制文案，改这一处就够。

    后端能力：POST /report 的 targetType=4（私聊消息）/ 8（群消息）。
    两张表各自自增 id，群消息绝不能按 4 提交——否则管理员会按同一个 id
    去 chat_message 里取到另一条私信的作者与正文。
  -->
  <ReportSheet
    v-model="open"
    :target-type="targetType"
    :target-id="messageId"
    sheet-title="举报这条消息"
    :submit-fn="submit"
  />
</template>

<script setup>
import { computed } from 'vue'
import { submitReport } from '@/api/review'
import ReportSheet from '@/components/post/ReportSheet.vue'

const props = defineProps({
  /** 开合（v-model） */
  modelValue: { type: Boolean, default: false },
  /** 被举报消息 id */
  messageId: { type: [Number, String], default: null },
  /** 4=私聊消息 / 8=群消息（openapi 契约的 enum） */
  targetType: { type: Number, default: 4 },
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
