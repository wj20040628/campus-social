<template>
  <!--
    帖子详情的吸底评论输入区(PostDetail.vue.md §1.1)
    抽成组件的原因:@ 提及浮层 + 输入行 + 发送失败提示 + 键盘让位,
    一整套是 90 行模板 + 一个状态机,塞进 PostDetail.vue 会顶破 300 行门禁。

    ## 键盘让位为什么用 sticky + translateY 而不是 fixed
    iOS 上 `position: fixed` 的元素在键盘弹起时会跟着 layout viewport 乱飘。
    sticky + translateY(calc(-1 * var(--kb-h))) 由 useKeyboardViewport 驱动,
    **两种方案不要混用** —— 混用会出现「键盘弹两次、输入区跑两次」的 double-shift。

    ## 发送失败必须保留草稿
    用户写了一百多字,点发送,网络断了,草稿被清空 —— 这是评论区最伤人的一种失败。
  -->
  <div class="cc">
    <!--
      回复态提示条(契约 CreateCommentDTO 的 parentId / level / replyToUserId)
      「回复 @李四」+ 一个可点的 ×。不发 @ 提及 —— 回复某人 ≠ @ 某人:
      @ 走 mentionUserIds 发 type=9 通知,回复走层级通知,两套通道。
    -->
    <p v-if="replyTo" class="cc__replying">
      <span class="cc__replying-text">回复 {{ replyName }}</span>
      <button type="button" class="cc__replying-x" aria-label="取消回复" @click="emit('cancel-reply')">
        <el-icon :size="14"><Close /></el-icon>
      </button>
    </p>

    <!-- @ 提及浮层：横排头像条，移动端 40px（设计 §1.2） -->
    <div v-if="mentionOpen" class="cc__mention">
      <ul v-if="list.length" class="cc__mention-strip">
        <li v-for="u in list" :key="u.id">
          <button type="button" class="cc__mention-cell" @mousedown.prevent="pick(u)">
            <el-avatar :size="40" :src="resolveUrl(u.avatarUrl)" />
            <span class="cc__mention-name">{{ displayName(u) }}</span>
          </button>
        </li>
      </ul>
      <p v-else class="cc__mention-empty">
        {{ keyword ? '没搜到这个人' : '还没有可 @ 的人（关注/粉丝为空），键入搜索试试' }}
      </p>
    </div>

    <!-- 发送失败提示贴在输入行上方,草稿一个字都不动 -->
    <p v-if="error" class="cc__error" role="alert">{{ error }}</p>

    <div class="cc__row">
      <el-input
        ref="inputRef"
        v-model="text"
        type="textarea"
        :rows="2"
        :maxlength="MAX_LEN"
        class="cc__box"
        :placeholder="placeholder"
        @input="onInput"
        @blur="onBlur"
      />
      <button
        type="button"
        class="cc__at"
        :class="{ 'cc__at--on': mentionOpen }"
        :aria-label="mentionOpen ? '收起提及列表' : '提及同学'"
        @click="toggle"
      >
        <span class="cc__at-glyph" aria-hidden="true">@</span>
      </button>
      <button
        type="button"
        class="cc__send"
        :disabled="!canSend"
        aria-label="发送评论"
        @click="send"
      >
        <span class="cc__send-glyph" aria-hidden="true"></span>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * 评论输入区。除「把评论发出去」以外的一切都在这里:
 * @ 提及状态机(在 useMention)、键盘让位(在 useKeyboardViewport)、字数上限。
 *
 * ## 为什么提交走 prop 而不是 emit
 * emit 是同步的:父组件在监听里发请求,组件拿不到 Promise,
 * 就无法判断「这次到底成没成」—— 而**失败时保留草稿**(本页最重要的失败语义)
 * 恰恰要求组件知道结果。所以这里收一个返回 Promise 的函数 prop。
 */
import { ref, computed, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useMention } from '@/composables/useMention'
import { useKeyboardViewport } from '@/composables/useKeyboardViewport'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'

const MAX_LEN = 2000

const props = defineProps({
  /** 当前用户 id。null = 未登录,此时 @ 提及没有推荐来源 */
  myId: { type: [Number, String], default: null },
  /**
   * 提交函数,返回 Promise。resolve = 成功(清草稿),reject = 失败(保留草稿)。
   * @param {{content:string, mentionUserIds:number[], replyTo:object|null}} payload
   */
  submit: { type: Function, required: true },
  /** 外部触发聚焦(评论区空态的「说一句」按钮会自增它) */
  focusSignal: { type: Number, default: 0 },
  /**
   * 正在回复的评论节点(CommentRow 递归 emit 上来的那个)。
   * **只用来显示「回复 @xxx」与算层级**,不参与 mentionUserIds ——
   * 回复与 @ 是两套通知通道,混在一起会给同一个人发两条红点。
   */
  replyTo: { type: Object, default: null },
})

const emit = defineEmits(['cancel-reply'])

const text = ref('')
const error = ref('')
const sending = ref(false)
const inputRef = ref(null)

const {
  mentionOpen, mentionList, mentionKw: keyword, mentionIds,
  onInput, toggle, pick, onBlur, reset,
} = useMention(inputRef, computed(() => props.myId), text)

/** 键盘弹起时把高度写到 :root,本组件只负责 translateY 让位 */
useKeyboardViewport()

const canSend = computed(() => text.value.trim().length > 0 && !sending.value)

const replyName = computed(() => {
  const u = props.replyTo?.user
  return u ? displayName(u) : '该评论'
})

/**
 * placeholder 三态。回复态优先于 @ 态 —— 两个都成立时(正在回复且打开了 @ 面板),
 * 「回复 @李四」才是用户此刻真正要回答的问题。
 */
const placeholder = computed(() => {
  if (props.replyTo) return `回复 ${replyName.value}`
  if (mentionOpen.value && !keyword.value) return '@ 输入搜索'
  return '写下你的评论，点 @ 提及同学'
})

/**
 * 进入回复态时聚焦。
 * 不聚焦等于用户点了「回复」还要再点一次输入框 —— 那一下会让他以为按钮没生效。
 */
watch(
  () => props.replyTo,
  (v) => {
    if (v) inputRef.value?.focus()
  },
)

// 空态的「说一句」按钮:自增 focusSignal 就能聚焦,不用父组件直接摸 DOM
watch(
  () => props.focusSignal,
  (n) => {
    if (n > 0) inputRef.value?.focus()
  },
)

/**
 * 发送。**失败时保留草稿** —— 用户写了一百多字,点发送,网络断了,
 * 草稿被清空是评论区最伤人的一种失败(PostDetail.vue.md §3)。
 * @returns {Promise<boolean>} 是否发送成功
 */
async function send() {
  if (!canSend.value) return false
  sending.value = true
  error.value = ''
  try {
    // replyTo 整个传上去,层级与 replyToUserId 由父组件用 useCommentTree 算。
    // 这里不自己算 —— 算层级需要整棵树的索引,组件里没有,硬算只能靠 parentId 猜。
    await props.submit({
      content: text.value.trim(),
      mentionUserIds: mentionIds.value,
      replyTo: props.replyTo,
    })
    text.value = ''
    error.value = ''
    reset()
    return true
  } catch (e) {
    error.value = e?.userMessage || '没发出去，再点一次发送'
    return false
  } finally {
    sending.value = false
  }
}

function clear() {
  text.value = ''
  error.value = ''
  reset()
}

defineExpose({ send, clear, focus: () => inputRef.value?.focus() })
</script>

<style scoped src="./CommentComposer.css"></style>