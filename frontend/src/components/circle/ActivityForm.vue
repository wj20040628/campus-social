<template>
  <!--
    「发布活动」表单(CircleDetail.vue.md §1.3,红线 §8.1 第 2 条)

    手机:el-drawer 全屏(direction=bottom + 100%)
    桌面:el-dialog 居中 480px
    —— 两者共用同一份表单内容,字段与校验不会漂移。
  -->
  <el-drawer
    v-if="isMobile"
    :model-value="modelValue"
    direction="btt"
    size="100%"
    :with-header="false"
    append-to-body
    class="af af--drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="af__bar">
      <button
        type="button"
        class="af__bar-btn"
        @click="$emit('update:modelValue', false)"
      >
        取消
      </button>
      <h2 class="af__bar-title">发布活动</h2>
      <button
        type="button"
        class="af__bar-btn af__bar-btn--primary"
        :disabled="submitting"
        @click="$emit('submit')"
      >
        {{ submitting ? '发布中' : '发布' }}
      </button>
    </div>

    <FormBody
      :form="form"
      :error="error"
      label-position="top"
      class="af__body"
    />

    <div class="af__foot">
      <SafeArea position="bottom" />
    </div>
  </el-drawer>

  <el-dialog
    v-else
    :model-value="modelValue"
    title="发布活动"
    width="480px"
    append-to-body
    align-center
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <FormBody :form="form" :error="error" label-position="left" label-width="80px" />

    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="$emit('submit')">发布</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 发布活动表单
 *
 * ## 为什么手机用 el-drawer 而不是自己写抽屉
 *
 * 需要的是「全屏 + 吸底操作栏 + 安全区 + Esc 关闭 + 焦点陷阱 + 遮罩点击关闭」。
 * 自己写一遍,最常漏的是后两条(遮罩与焦点陷阱)——
 * 键盘用户 Tab 出抽屉后焦点会跑到被遮住的页面上,读屏会读出抽屉外的内容。
 * el-drawer 已提供,视觉上只需覆盖成设计的样子(无顶部圆角、满高)。
 *
 * ## 为什么表单体抽成 FormBody
 *
 * 两个外壳的 props 差异大到「一个组件 + 一堆 v-if」比两个组件更乱,
 * 但**字段与校验必须只有一份**。FormBody 就是那份,两个外壳只负责壳。
 */
import { useIsMobile } from '@/composables/useBreakpoint'
import SafeArea from '@/components/mobile/SafeArea.vue'
import FormBody from './ActivityFormBody.vue'

defineProps({
  modelValue: { type: Boolean, default: false },
  form: { type: Object, required: true },
  submitting: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

defineEmits(['update:modelValue', 'submit'])

const isMobile = useIsMobile()
</script>

<style scoped>
.af__bar {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  height: 56px;
  padding: 0 var(--ys-space-2);
  margin-bottom: var(--ys-space-4);
  border-bottom: 1px solid var(--ys-border-soft);
  flex: none;
}

.af__bar-title {
  flex: 1;
  margin: 0;
  text-align: center;
  font-size: var(--ys-text-lg);
  letter-spacing: var(--ys-ls-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.af__bar-btn {
  min-width: 56px;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: none;
  background: transparent;
  color: var(--ys-muted);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
}

.af__bar-btn--primary {
  color: var(--ys-accent);
  font-weight: var(--ys-weight-medium);
}

.af__bar-btn:disabled {
  color: var(--ys-meta);
  cursor: not-allowed;
}

.af__bar-btn:not(:disabled):active {
  opacity: 0.7;
}

.af__body {
  flex: 1;
  min-height: 0;
}

.af__foot {
  flex: none;
}

/*
 * 覆盖 el-drawer 的视觉,让它变成「全屏表单页」而不是「底部抽屉」:
 * 顶部圆角去掉、满高。这两条不改就会留下一条 16px 的圆角缝,
 * 在全屏表单的语境下像是没做完。
 * el-drawer 挂在 body 上,scoped 属性选择器命中不了,故用 :deep + 全局选择器。
 */
:global(.af--drawer .el-drawer__body) {
  padding: 0 var(--ys-space-4);
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

:global(.af--drawer) {
  border-radius: 0;
}
</style>
