<template>
  <!--
    圈子发帖（2026-10-21 从常驻框改成浮层）。

    【为什么改】原来它是一张常驻在 Tab 正下方的卡片 ——
    那是整页最贵的位置（视线第一落点），却放着一个 99% 时间空着的表单。
    结果「能不能发」压过了「别人说了什么」，帖子列表被推下去。
    现在缩成一行触发条（在 CircleDetail 里），点开才是这张表单。

    【两种外壳，同一份表单】手机用底部抽屉（与集市留言抽屉同一语言），
    桌面用居中小弹窗（桌面视口高，抽屉从底部升起离视线焦点太远）。
    el-drawer 在桌面也能用，但 direction="btt" 会横跨整个宽度，很怪，
    所以这里按断点分叉，两端都用各自最自然的那一种。
  -->
  <el-drawer
    v-if="isMobile"
    v-model="open"
    direction="btt"
    size="auto"
    :with-header="false"
    class="cf-sheet"
    @closed="onClosed"
  >
    <div class="cf">
      <div class="cf__head">
        <span class="cf__title">在「{{ circleName }}」发帖</span>
        <button type="button" class="cf__close" aria-label="关闭" @click="open = false">
          <el-icon :size="18"><Close /></el-icon>
        </button>
      </div>
      <ComposerBody
        ref="bodyRef"
        :circle-name="circleName"
        :content="content"
        :media="media"
        :posting="posting"
        :uploading="uploading"
        :error="error"
        @submit="$emit('submit')"
        @update:content="$emit('update:content', $event)"
        @update:media="$emit('update:media', $event)"
      />
    </div>
  </el-drawer>

  <Teleport v-else to="body">
    <div v-if="open" class="cf-mask" @click.self="open = false">
      <div class="cf-dialog" role="dialog" aria-modal="true" aria-label="发帖">
        <div class="cf__head">
          <span class="cf__title">在「{{ circleName }}」发帖</span>
          <button type="button" class="cf__close" aria-label="关闭" @click="open = false">
            <el-icon :size="18"><Close /></el-icon>
          </button>
        </div>
        <ComposerBody
          ref="bodyRef"
          :circle-name="circleName"
          :content="content"
          :media="media"
          :posting="posting"
          :uploading="uploading"
          :error="error"
          @submit="$emit('submit')"
          @update:content="$emit('update:content', $event)"
          @update:media="$emit('update:media', $event)"
        />
      </div>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 圈子发帖浮层：负责「何时、以什么外壳显示表单」，不含表单本身。
 *
 * 表单在 CircleComposerForm.vue —— 手机抽屉与桌面弹窗用的是同一份，
 * 写两遍意味着那 80 行 UI 要维护两份，改一处必然漏另一处。
 *
 * 两种外壳的选择：
 *   - 手机（<768px）：el-drawer + direction="btt"，与集市留言抽屉同一语言
 *   - 桌面：自绘居中弹窗。桌面视口高，抽屉从底部升起离视线焦点太远，
 *           鼠标也要长距离移动；居中小弹窗放在同一处（MktRiskNotice 是先例）
 */
import { computed, nextTick, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useIsMobile } from '@/composables/useBreakpoint'
import ComposerBody from './CircleComposerForm.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  circleName: { type: String, default: '' },
  content: { type: String, default: '' },
  media: { type: Array, default: () => [] },
  posting: { type: Boolean, default: false },
  uploading: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'submit', 'update:content', 'update:media'])

/** 手机走抽屉，桌面走居中弹窗（断点与全站一致） */
const isMobile = useIsMobile()

const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const bodyRef = ref(null)

/**
 * 打开后聚焦输入框。
 *
 * 必须等浮层动画结束（260ms）再聚焦：iOS 上过早聚焦会让键盘在抽屉还没就位时弹起，
 * 结果输入框被顶到屏幕上方看不见。这个数值与集市留言抽屉保持一致。
 */
watch(
  () => props.modelValue,
  (v) => {
    if (!v) return
    nextTick(() => setTimeout(() => bodyRef.value?.focus(), 260))
  },
)

/** 关闭时**不清草稿**：误触关闭后重新打开能接着写，与集市留言抽屉同一口径 */
function onClosed() {
  /* 有意留空 */
}

defineExpose({ focus: () => bodyRef.value?.focus() })
</script>

<style scoped src="./CircleComposer.css"></style>
