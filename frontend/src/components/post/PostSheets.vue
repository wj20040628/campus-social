<template>
  <!--
    帖子详情的两层浮层:更多操作表 / 举报
    (PostDetail.vue.md 行 1 的「更多」)

    抽成组件的**真实理由**:这两层共用同一个「页面级浮层组」的语义 ——
    它们全部由顶栏那个「更多」按钮或它的下游触发,生命周期绑在一起,
    而且都是「先关上一层、再开下一层」的接力(openReport 就是关 more 开 report)。
    散在页面里时,接力逻辑(哪层关哪层)要跨几百行读才看得清。

    举报为什么不单独开一页(设计如此):5 类原因 + 「其他」要收一段补充说明,
    放独立页会多一次跳转;而本页已有吸底评论输入区,第三个全屏层叠上去
    在手机上会打架。所以三层叠在同一个组件里,互斥显示。
  -->
  <ActionSheet
    :model-value="moreOpen"
    title="这篇帖子"
    @update:model-value="emit('update:moreOpen', $event)"
  >
    <button type="button" class="asheet__item asheet__item--danger" @click="openReport">
      举报这篇帖子
    </button>
    <button type="button" class="asheet__item" @click="copyLink">复制链接</button>
    <button
      v-if="!isAuthor && authorId && userStore.hasToken"
      type="button"
      class="asheet__item"
      @click="hideAuthor"
    >
      不看这个作者的动态
    </button>
  </ActionSheet>

  <ReportSheet
    :model-value="reportOpen"
    :target-type="2"
    :target-id="postId"
    sheet-title="举报这篇帖子"
    :submit-fn="submitReport"
    @update:model-value="emit('update:reportOpen', $event)"
  />

</template>

<script setup>
/**
 * 浮层组的**状态由父组件持有**(v-model:xxx),本组件只管「什么时候开哪一层」。
 *
 * 为什么不用 defineModel:三层各自独立开关,父组件要能从外部主动关掉其中一层
 * (比如加载完帖子后自动收起更多表)。用 defineModel 也能做,但三层就六个
 * defineModel + 六个 emit,签名比 props/emit 更难读。props + emit 更直白。
 */
import { ElMessage } from '@/utils/feedback'
import { copyText } from '@/utils/clipboard'
import { submitReport } from '@/api/review'
import { blacklist } from '@/api/relation'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import ReportSheet from '@/components/post/ReportSheet.vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const props = defineProps({
  moreOpen: { type: Boolean, default: false },
  reportOpen: { type: Boolean, default: false },
  postId: { type: [Number, String], default: null },
  authorId: { type: [Number, String], default: null },
  isAuthor: { type: Boolean, default: false },
})

const emit = defineEmits([
  'update:moreOpen',
  'update:reportOpen',
  /** 拉黑成功 → 父组件跳首页(拉黑后留在详情页没意义,用户看不到变化) */
  'author-hidden',
])

/** 接力:关「更多」的同时开「举报」。顺序不能反,否则两层会同时在场 */
function openReport() {
  emit('update:moreOpen', false)
  emit('update:reportOpen', true)
}

async function copyLink() {
  emit('update:moreOpen', false)
  // http 源没有 clipboard API，copyText 内部退化到 execCommand
  const ok = await copyText(window.location.href)
  if (ok) ElMessage.success('链接已复制')
  else ElMessage.warning('这个浏览器不让复制，长按地址栏试试')
}

/**
 * 不看这个作者。
 *
 * 后端没有独立的「不看」端点,能落库的是拉黑。拉黑会连带**双向取关**,
 * 比「不想在广场看到这个人」重得多 —— 所以这里明说代价,
 * 让用户自己决定,而不是用一个轻的词描述一个重的动作。
 */
async function hideAuthor() {
  emit('update:moreOpen', false)
  try {
    await blacklist(props.authorId)
    ElMessage.success('已拉黑，也已取消关注。之后看不到他的动态')
    emit('author-hidden')
  } catch {
    /* request.js 已弹错,留在原地 */
  }
}

</script>