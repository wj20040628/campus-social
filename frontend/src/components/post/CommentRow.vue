<template>
  <!--
    单条评论节点(递归渲染)
    ------------------------------------------------------------------
    为什么递归而不是拆 CommentRow / CommentSubRow 两份:
    三级是二级的一个子集,拆两份几乎一样的模板后,「加第四级」或
    「二级样式微调」都要改两处。递归一份就够,层级上限由
    replySpecFor 封顶(level ≤ 3)+ 缩进 clamp 两重保证。

    缩进封顶在 MAX_VISUAL_DEPTH:设计 §4「禁止评论区楼层缩进超过 2 层」——
    375px 下缩进三层后正文只剩不到 200px 宽,长评论会折成五六行。
  -->
  <li :id="`comment-${node.id}`" class="c-item" :class="`c-item--d${depth}`">
    <InitialAvatar :size="depth === 0 ? 32 : 24" :src="node.user?.avatarUrl ? resolveThumb(node.user.avatarUrl) : ''" :seed="node.user?.id" :name="node.user ? displayName(node.user) : ''" />

    <div class="c-body">
      <p class="c-nick">
        {{ node.user ? displayName(node.user) : '匿名' }}
        <!--
          回复前缀措辞是「回复 X」而不是「@X」或「通知 X」。
          契约写明降级时「显示的人」与「收到通知的人」可能不一致,
          「回复 X」只陈述数据,不给用户「X 一定会收到红点」的承诺。
        -->
        <span v-if="node.replyToNickname" class="c-reply">回复 {{ node.replyToNickname }}</span>
      </p>

      <!--
        clamp 类**恒定挂着**,超长与否由下面的 isLong 决定是否显示「展开」。
        line-clamp 元素上 scrollHeight 是完整内容高度、clientHeight 是钳制后的高度,
        两者一比就知道有没有被截断 —— 不需要先解绑 clamp 再量。
      -->
      <p ref="textRef" class="c-text" :class="{ 'c-text--clamp': isLong && !expanded }">
        <template v-for="(seg, si) in segmentsOf(node)" :key="si">
          <a
            v-if="seg.link"
            class="c-mention"
            href="#"
            @click.stop.prevent="emit('go-user', seg.link)"
          >@{{ seg.text }}</a>
          <span v-else>{{ seg.text }}</span>
        </template>
        <button
          v-if="isLong"
          type="button"
          class="c-more"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >{{ expanded ? '收起' : '展开' }}</button>
      </p>

      <p class="c-meta">
        <span class="c-time">{{ node.createTime }}</span>
        <button
          v-if="canReply"
          type="button"
          class="c-reply-btn"
          :aria-label="`回复 ${node.user ? displayName(node.user) : '匿名'}`"
          @click="emit('reply', node)"
        >回复</button>
      </p>

      <!--
        折叠:子评论超过 2 条才默认收起。
        阈值定 2 是因为「藏起来了」和「没加载出来」在视觉上难区分,
        2 条以内直接铺开,超过才让用户自己决定要不要展开。
      -->
      <button
        v-if="node.children.length > COLLAPSE_AT"
        type="button"
        class="c-toggle"
        :aria-expanded="open"
        @click="open = !open"
      >{{ open ? '收起回复' : `展开 ${node.children.length} 条回复` }}</button>

      <!--
        溢出入口(契约 childrenOverflowCount)。
        后端 children 最多给 20 条,超出的条数在这里 —— 不给入口就等于
        静默截断,契约明确禁止。文案里的数字用 totalForParent(总条数)
        减已加载数,而不是直接念 childrenOverflowCount:后者是「被截断的
        条数」,用户要的是「一共几条」。
      -->
      <button
        v-if="open && overflowState"
        type="button"
        class="c-more-replies"
        :disabled="overflowState.loading"
        @click="$emit('load-children', node)"
      >
        <template v-if="overflowState.loading">加载中…</template>
        <template v-else-if="overflowState.error">{{ overflowState.error }}</template>
        <template v-else-if="overflowState.done">已加载全部回复</template>
        <template v-else>还有 {{ overflowLeft }} 条回复</template>
      </button>

      <!--
        到 MAX_RENDER_DEPTH 就不往下渲染了 —— 脏数据能构出环(A→B→A),
        不设硬上限会栈溢出、整页白屏。宁可少显示几条。
        缩进仍然 clamp 到 MAX_VISUAL_DEPTH:那是视觉约束,和数据无关。
      -->
      <ul v-if="open && node.children.length && depth < MAX_RENDER_DEPTH" class="c-subs">
        <CommentRow
          v-for="ch in node.children"
          :key="ch.id"
          :node="ch"
          :depth="Math.min(depth + 1, MAX_VISUAL_DEPTH)"
          :my-id="myId"
          :overflow-of="overflowOf"
          @reply="emit('reply', $event)"
          @go-user="emit('go-user', $event)"
          @load-children="emit('load-children', $event)"
        />
      </ul>
    </div>
  </li>
</template>

<script setup>
/**
 * 单条评论节点。**独立成文件而不是写在 PostComments.vue 里**:
 * 它要递归引用自己 —— SFC 里靠 import 自己构成循环依赖,
 * 编译器能处理但读起来像 bug。
 */
import { ref, computed, watch, nextTick } from 'vue'
import { splitComment } from '@/composables/usePostDetail'
import { MAX_RENDER_DEPTH, MAX_VISUAL_DEPTH } from '@/composables/commentDepth'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import InitialAvatar from '@/components/common/InitialAvatar.vue'

/** 子评论超过这个数才默认折叠 */
const COLLAPSE_AT = 2

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  /** 登录用户 id。为空(游客)时不给回复入口 —— 发评论需要身份 */
  myId: { type: [Number, String], default: null },
  /**
   * 取溢出状态与加载函数的访问器(由 PostComments 传下)。
   * 用函数而不是直接把状态对象传下来:状态是响应式的,函数能在渲染时
   * 拿到最新值;传对象则每层都要自己解 ref,递归组件里容易漏。
   */
  overflowOf: { type: Function, default: null },
})

const emit = defineEmits(['reply', 'go-user', 'load-children'])

const expanded = ref(false)
const open = ref(true)
const textRef = ref(null)
const isLong = ref(false)

/**
 * 本节点的溢出状态。`childrenOverflowCount > 0` 或已加载过溢出页才需要。
 * 两个条件都要 —— 加载完后 count 仍是 18(后端不会改它),但那时
 * 该显示的是「已加载全部」而不是「还有 18 条」。
 */
const overflowState = computed(() => {
  const of = props.overflowOf
  if (!of) return null
  // 传下来的是 stateOf **函数本身**(PostComments 解构后直接绑定),
  // 所以这里直接调用;早期写成 of.stateOf(id) —— 函数上没有这个方法,
  // TypeError 打断整棵评论子树的渲染,评论区/输入框全部消失。
  const s = of(props.node.id)
  const hasOverflow = Number(props.node.childrenOverflowCount) > 0
  return s || hasOverflow ? s || { loading: false, error: '', done: false } : null
})

/**
 * 还要加载几条 = 总条数(totalForParent)- 已加载数。
 * 拿不到 total 时退回 childrenOverflowCount(后端给了但还没加载过的场景)。
 */
const overflowLeft = computed(() => {
  const s = overflowState.value
  const total = s?.total || 0
  const loaded = props.node.children.length
  if (total > 0) return Math.max(0, total - loaded)
  return Math.max(0, Number(props.node.childrenOverflowCount) || 0)
})

/**
 * 能不能回复。未登录、回复自己都不给入口。
 * 回复自己在语义上等于追加一句自言自语,后端也不拦,
 * 但入口藏掉比发出去再解释「不能回复自己」省事。
 */
const canReply = computed(() => props.myId != null && props.node.userId !== props.myId)

function segmentsOf(c) {
  return splitComment(c)
}

/**
 * 量一次是否超 6 行(设计 §3「内容超长」)。
 *
 * **必须 DOM 测量,不能按字数估**:中文一行约 15 字、英文约 40 字,
 * 按字符数猜会在两种语言里各错一半 —— 估少了长评论永远不给「展开」,
 * 估多了短评论挂一个点不动的「展开」。
 */
function measure() {
  nextTick(() => {
    const el = textRef.value
    isLong.value = !!el && el.scrollHeight > el.clientHeight + 2
  })
}

// 内容变(重渲染/重挂载)时重量。immediate 让首屏的长评论也带「展开」
watch(() => props.node.content, measure, { immediate: true })
// 展开态下高度变了,收起时要重新判定
watch(expanded, () => nextTick(measure))
</script>

<style scoped src="./CommentRow.css"></style>
