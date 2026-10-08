<template>
  <!--
    表单级错误,「点这里重试」可点。
    设计 §3 网络失败态原文:「网络好像断了,点这里重试」,其中「点这里」是可点文字。
    纯文本的话用户只能重填一遍整个表单 —— 网络失败恰恰是最不该让人重填的失败。
  -->
  <p class="auth-error" role="alert">
    <template v-if="retryable">
      <span>{{ textBefore }}</span>
      <!--
        键盘可达:用 button 而不是给 span 绑 click,
        否则读屏不播报、Tab 也跳不到。
      -->
      <button type="button" class="auth-error__retry" @click="$emit('retry')">
        {{ retryText }}
      </button>
      <span>{{ textAfter }}</span>
    </template>
    <template v-else>{{ message }}</template>
  </p>
</template>

<script setup>
/**
 * 可重试的表单级错误。
 *
 * 纯展示 + 抛事件:重试要重发什么请求由页面决定(登录页重发登录,
 * 注册页重发注册),本组件不知道,也不该知道。
 *
 * 拆句子的原因:「点这里」要独立成一个 button,就不能让整句都塞进
 * message 字符串里 —— 那样只能整句绑 click,连字和标点都会跟着变可点的。
 */
import { computed } from 'vue'

const props = defineProps({
  /** 完整文案。可重试时会用前三个字段重新拼一遍 */
  message: { type: String, default: '' },
  /** 网络失败时置 true,才渲染可点的「点这里」 */
  retryable: { type: Boolean, default: false },
})

defineEmits(['retry'])

/**
 * 「点这里」三段。message 保留完整文案是为了给读屏一个连贯句子。
 *
 * 三个都是独立的 computed 而不是解包一次 computed(...).value:
 * 后者只在 setup 时求值一次,message 变了(三 Tab 切换、重新提交)分段不会更新,
 * 表现为「文案换了但'点这里'还指着上一句」。
 */
const parts = computed(() => {
  const m = props.message || ''
  const i = m.indexOf('，')
  if (i < 0) return [m, '点这里重试', '']
  return [m.slice(0, i), '点这里重试', m.slice(i)]
})

const textBefore = computed(() => parts.value[0])
const retryText = computed(() => parts.value[1])
const textAfter = computed(() => parts.value[2])
</script>

<style scoped>
/* 段级样式已在 Auth.css 的 .auth-error 里定义,这里只做 scoped 能覆盖到的部分。
   之所以不整段重写:scoped 下 .auth-error 会带上本页的属性选择器,
   两个文件写同一套属性反而容易走偏。 */

.auth-error__retry {
  display: inline;
  padding: 0;
  border: 0;
  background: none;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  color: var(--ys-accent);
  text-decoration: underline;
  cursor: pointer;
}

.auth-error__retry:hover {
  color: var(--ys-accent-hover);
}

.auth-error__retry:active {
  opacity: 0.7;
}

/* 键盘焦点:文字按钮没有浏览器默认描边,要自己给。
   focus-visible 而非 focus —— 鼠标点不该留一圈框 */
.auth-error__retry:focus-visible {
  outline: 2px solid var(--ys-accent);
  outline-offset: 2px;
  border-radius: var(--ys-radius-sm);
}
</style>
