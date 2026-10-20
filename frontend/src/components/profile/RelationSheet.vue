<template>
  <!--
    粉丝 / 关注列表抽屉(2026-10-20)。
    这两个列表原先占着内容 Tab 的两格,把「收藏」「赞过」挤没了;
    改成统计行的两个数字可点,点了从底部拉起列表。
    列表本身复用 UserList,空态文案按视角给,不在这里重写一套行样式。
  -->
  <ActionSheet v-model="open" :title="title">
    <UserList
      :list="list"
      :loading="loading"
      :empty-title="emptyTitle"
      :empty-desc="emptyDesc"
      :empty-action="emptyAction"
      @empty-action="$emit('empty-action')"
    />
  </ActionSheet>
</template>

<script setup>
import { computed } from 'vue'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import UserList from '@/components/UserList.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** followers | following */
  tab: { type: String, default: 'followers' },
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  /** 是否在看自己的主页:空态文案要跟着变 */
  isMe: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'empty-action'])

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const isFollowers = computed(() => props.tab === 'followers')

const title = computed(() => (isFollowers.value ? '粉丝' : '关注'))

const emptyTitle = computed(() => {
  if (isFollowers.value) return props.isMe ? '还没有人关注你' : '还没有人关注 TA'
  return props.isMe ? '你还没关注任何人' : 'TA 还没关注任何人'
})

/** 空态给行动而不是描述现象(AC-19):粉丝空态去发一条,关注空态去广场逛 */
const emptyDesc = computed(() => (
  isFollowers.value ? '多发几条，就有人来了' : '去圈子里看看大家都在聊什么'
))

const emptyAction = computed(() => (isFollowers.value ? '去发一条' : '去逛逛'))
</script>
