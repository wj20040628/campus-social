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
    **这段历史注释已过期**:契约 57e20e1 更正为「列表端点返回一级 + 每条的
    children 子树(每节点 20 条上限,超限数放 childrenOverflowCount,不得静默截断)」,
    后端本轮已按新契约实现。所以现在走的是 buildCommentForest 的**后端下发树**分支,
    按 parentId 自建只保留作降级容错(旧版后端 / 某节点没下发 children 时)。

    ## 溢出补齐在本组件而不是 CommentRow 里
    CommentRow 只管渲染与发事件(它要递归引用自己,不能再塞状态)。
    按节点独立的游标/加载中/错误在 useCommentChildren,
    本组件的职责是把加载结果**作为入参**并进建树 —— 直接 push 到
    node.children 会被下一次 computed 重算覆盖(见 loadChildren 的注释)。
  -->
  <section class="comments">
    <h2 class="comments__head">评论 {{ countText }}</h2>

    <ul v-if="roots.length" class="comments__list">
      <CommentRow
        v-for="n in roots"
        :key="n.id"
        :node="n"
        :my-id="myId"
        :overflow-of="overflowStateOf"
        @reply="emit('reply', $event)"
        @go-user="emit('go-user', $event)"
        @load-children="loadChildren"
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
import { computed, ref, toRef } from 'vue'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { capCount } from '@/composables/usePostDetail'
import { buildCommentForest } from '@/composables/useCommentTree'
import { useCommentChildren } from '@/composables/useCommentChildren'
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
  /**
   * 帖子 id。溢出补齐端点是 `/post/{id}/comment/{commentId}/children`,
   * 路径里要用 —— 不传则点「还有 N 条回复」只会静默无反应。
   */
  postId: { type: [Number, String], default: null },
})

const emit = defineEmits(['ask', 'go-user', 'reply'])

/**
 * 溢出补齐(契约 childrenOverflowCount)。
 *
 * ## 修复前的状态(留档,免得日后有人去找一个不存在的名字)
 *
 * 模板当时绑的是 `:overflow-of="overflowApi"` 与 `@load-children="loadChildren"`,
 * 而 **`overflowApi` 既不是 prop 也不是本地定义** —— 全项目搜不到这个名字。
 * `loadChildren` 同样只出现在模板里,没有对应的函数声明。
 *
 * Vue 编译能过(未定义的模板标识符在渲染时才解析),运行时 `overflowApi` 是
 * `undefined` —— CommentRow 拿到的 `overflowOf` 走 default `null`,
 * 于是溢出入口永远不显示:用户看到「楼上明明有 19 条回复,怎么只显示 1 条」。
 * **不报错、不白屏,只是永远少一个入口。**
 *
 * 修法:接入 useCommentChildren,把绑定改为下面这个真实的 `overflowStateOf`,
 * 并补上 `loadChildren`(见下)。所以现在模板里搜不到 `overflowApi` 是正常的 ——
 * 它已经被替换掉了,不是「还没定义」。
 *
 * ## 为什么数据层是 useCommentChildren 而不是本地写
 *
 * 溢出是**按节点独立**的状态(各自的游标 / 加载中 / 错误)。放进
 * useCommentChildren 后,本文件只负责把结果并进树,状态只有一份。
 */
const {
  stateOf: overflowStateOf,
  loadMore: loadChildrenMore,
} = useCommentChildren(toRef(props, 'postId'))

/**
 * 已加载的溢出页:父 id → 追加的子回复数组。
 *
 * 必须是 **ref** 而不是普通变量:`buildCommentForest` 每次重算都要求一份
 * 完整的 extraByParent,外部持有的 Map 引用不会自动进入 computed 依赖 ——
 * 用普通变量的话,点「展开更多」后界面不更新(数据到了但没进 computed)。
 */
const extraByParent = ref(new Map())

/**
 * 把某个节点的溢出页并进树。
 *
 * 为什么不用 `node.children.push(...)`:森林是 computed 每次重算的产物,
 * 外部改它的节点,下一次 props 变化就被覆盖 —— 表现是「点开加载了,
 * 刷新一下又只剩 20 条」。作为入参传进去才能活过重算(见 useCommentTree 的 mergeExtra)。
 */
async function loadChildren(node) {
  const list = await loadChildrenMore(node)
  if (!list.length) return
  const pid = node?.id
  const prev = extraByParent.value.get(pid) || []
  // 同 id 去重:溢出页第一页可能与 children 里的 20 条重叠(按 id 游标分页,
  // 边界可能落在已给的条目上),重复渲染会让「共 N 条」与实际条数对不上。
  const seen = new Set(prev.map((c) => c.id))
  const merged = [...prev]
  for (const c of list) {
    if (c && c.id != null && !seen.has(c.id)) { seen.add(c.id); merged.push(c) }
  }
  extraByParent.value = new Map(extraByParent.value).set(pid, merged)
}

const forest = computed(() => buildCommentForest(props.comments, extraByParent.value))
const roots = computed(() => forest.value.roots)

/** 优先用后端给的总数;没有(未登录/字段缺)才退回可见条数 */
const countText = computed(() => {
  const t = Number(props.total)
  return capCount(Number.isFinite(t) && t >= 0 ? t : props.comments.length)
})

defineExpose({ index: computed(() => forest.value.index) })
</script>

<style scoped src="./PostComments.css"></style>
