<template>
  <!--
    评论区(PostDetail.vue.md 行 8)

    抽成组件的理由不是行数,是**这块有独立的空态语义**:
    评论区为空时主句用 lg 不用 2xl —— 它不该抢正文的视觉重量,
    而页面级的空态(帖子没了 / 加载中)是要抢的。两种空态放在同一个文件里,
    改样式时很容易把权重改串。

    组件自持 @ 提及的**渲染**(splitComment),但**输入**在 CommentComposer:
    发评论时要把 @ 提及写进正文,渲染和输入是同一套状态机的两端,
    两端必须共用同一个 splitComment,否则会出现「显示了 @某人 但发出去是纯文本」。
  -->
  <section class="comments">
    <h2 class="comments__head">评论 {{ countText }}</h2>

    <ul v-if="comments.length" class="comments__list">
      <li
        v-for="c in comments"
        :id="`comment-${c.id}`"
        :key="c.id"
        class="c-item"
      >
        <el-avatar :size="32" :src="resolveUrl(c.user?.avatarUrl)" />
        <div class="c-body">
          <p class="c-nick">
            {{ c.user ? displayName(c.user) : '匿名' }}
            <span v-if="c.replyToNickname" class="c-reply">回复 {{ c.replyToNickname }}</span>
          </p>
          <p class="c-text">
            <template v-for="(seg, si) in segmentsOf(c)" :key="si">
              <a v-if="seg.link" class="c-mention" @click.stop="emit('go-user', seg.link)">
                @{{ seg.text }}
              </a>
              <span v-else>{{ seg.text }}</span>
            </template>
          </p>
          <p class="c-time">{{ c.createTime }}</p>
        </div>
      </li>
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
import { capCount, splitComment } from '@/composables/usePostDetail'
import { displayName } from '@/utils/mask'
import { resolveUrl } from '@/utils/resolve'
import StateBlock from '@/components/common/StateBlock.vue'

const props = defineProps({
  comments: { type: Array, default: () => [] },
})

const emit = defineEmits(['ask', 'go-user'])

/** 999 封顶。与页面其他计数同一口径,避免「1.2k 评论」这种读不出来的数 */
const countText = computed(() => capCount(props.comments.length))

/**
 * 逐条渲染成「文本 / @提及链接」片段序列。
 *
 * 提成函数而不是在模板里 map:模板里写 .map() 加 v-for 需要额外的
 * computed 或方法调用,两种写法行数一样但后者可读性差一截。
 */
function segmentsOf(c) {
  return splitComment(c)
}
</script>

<style scoped src="./PostComments.css"></style>