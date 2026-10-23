<template>
  <!--
    成员行（2026-10-21 从 CircleMembers.vue 抽出）。

    抽出来的理由：三种角色用的是同一行结构，只有右侧「角色标签 / 私信按钮」不同。
    在父组件里 v-for 三遍会把模板撑得很长，而单文件门禁是 400 行。
  -->
  <div class="cmrow">
    <InitialAvatar
      :size="40"
      :src="member.avatarUrl ? resolveThumb(member.avatarUrl) : ''"
      :seed="member.userId"
      :name="member.nickname"
    />

    <div class="cmrow__body">
      <p class="cmrow__name">{{ member.nickname }}</p>
      <!--
        年级 · 学院：老用户可能没填（字典化之前注册的），
        所以只在有值时拼接 —— 直接插值会渲染出「 · 人工智能学院」这种前面带点的残句。
      -->
      <p v-if="metaText" class="cmrow__meta">{{ metaText }}</p>
    </div>

    <span v-if="roleText" class="cmrow__role" :class="roleClass">{{ roleText }}</span>
    <button
      v-else-if="showOp"
      type="button"
      class="cmrow__op"
      @click="$emit('chat')"
    >
      私信
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import { resolveThumb } from '@/utils/resolve'

const props = defineProps({
  /** CircleMemberVO：{ userId, nickname, avatarUrl, role, grade, dept } */
  member: { type: Object, required: true },
  /** 普通成员那一档给「私信」入口；圈主/管理显示角色标签 */
  showOp: { type: Boolean, default: false },
})

defineEmits(['chat'])

/** 圈主与管理显示标签（说明身份）；普通成员不显示标签，改用「私信」按钮 */
const roleText = computed(() => {
  if (props.member.role === 1) return '圈主'
  if (props.member.role === 2) return '管理'
  return ''
})

const roleClass = computed(() => (props.member.role === 1 ? 'cmrow__role--owner' : 'cmrow__role--admin'))

/**
 * 「2023级 · 人工智能学院」。
 * 两个字段可能都有、只有一个、或都没有 —— 分隔符只在需要时加。
 */
const metaText = computed(() => {
  const parts = [props.member.grade, props.member.dept].filter(Boolean)
  return parts.join(' · ')
})
</script>

<style scoped src="./CircleMemberRow.css"></style>
