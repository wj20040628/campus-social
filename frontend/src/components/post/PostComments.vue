<template>
  <!--
    评论区(PostDetail.vue.md 行 8)
    ------------------------------------------------------------------
    抽成组件的理由不是行数,是**这块有独立的空态语义**:
    评论区为空时主句用 lg 不用 2xl —— 它不该抢正文的视觉重量,
    而页面级的空态(帖子没了 / 加载中)是要抢的。两种空态放在同一个文件里,
    改样式时很容易把权重改串。

    组件自持 @ 提及的**渲染**(splitComment 在 CommentRow 里),
    但**输入**在 CommentComposer:发评论时要把 @ 提及写进正文,
    渲染和输入是同一套状态机的两端,两端必须共用同一个 splitComment,
    否则会出现「显示了 @某人 但发出去是纯文本」。

    ## 树还是平
    契约写明列表端点只返回一级,二三级由前端按 parentId 嵌套。
    真实后端目前也只返回一级(CommentServiceImpl:103 硬编码 level=1),
    所以 children 现在恒为空 —— 但**归一化与递归渲染已经写好**,
    后端放开二三级时前端零改动。不先写好等于把同一件事做两遍。
  -->
  <section class="comments">
    <h2 class="comments__head">评论 {{ countText }}</h2>

    <ul v-if="roots.length" class="comments__list">
      <CommentRow
        v-for="n in roots"
        :key="n.id"
        :node="n"
        :my-id="myId"
        @reply="emit('reply', $event)"
        @go-user="emit('go-user', $event)"
      />
    </ul>

    <!--
      空态:主句 lg 不用 2xl(见文件头注释)。
      「说一句」按钮把光标送进吸底输入框,而不是滚到底部(PostDetail.vue.md §3)。
    -->
    <StateBlock
      v-else
      variant="empty"
      title="还没有人搭话"
      description="说点什么，楼主在等回应"
      action-label="说一句"
      :icon="ChatLineSquare"
      @action="emit('ask')"
    />
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { capCount } from '@/composables/usePostDetail'
import { buildCommentForest } from '@/composables/useCommentTree'
import StateBlock from '@/components/common/StateBlock.vue'
import CommentRow from '@/components/post/CommentRow.vue'

const props = defineProps({
  /** 接口返回的**扁平**评论列表,树在内部建 */
  comments: { type: Array, default: () => [] },
  /**
   * 评论总数。标题用帖子级 commentCount 而不是列表长度 ——
   * 后端分页只给一页,用列表长度会显示「评论 3」而实际有 300 条。
   */
  total: { type: [Number, String], default: null },
  myId: { type: [Number, String], default: null },
})

const emit = defineEmits(['ask', 'go-user', 'reply'])

const forest = computed(() => buildCommentForest(props.comments))
const roots = computed(() => forest.value.roots)

/** 优先用后端给的总数;没有(未登录/字段缺)才退回可见条数 */
const countText = computed(() => {
  const t = Number(props.total)
  return capCount(Number.isFinite(t) && t >= 0 ? t : props.comments.length)
})

defineExpose({ index: computed(() => forest.value.index) })
</script>

<style scoped src="./PostComments.css"></style>
