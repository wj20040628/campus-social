<template>
  <!--
    帖子正文区(PostDetail.vue.md 行 2~5)
    = 作者行 + 正文 + 话题 chips + 媒体网格

    抽成组件的理由:**这一整块是纯展示,没有任何交互状态**。
    原来的实现里,它夹在「操作条(有 liked 状态)」和「评论区(要 emit 事件)」之间,
    读写状态和只读渲染混在同一层,改媒体布局要在一堆事件处理器里找位置。

    它是本页唯一**不 emit 任何交互事件**的区块(话题点击是唯一的出口),
    所以放在这里读代码时能立刻看出:「哦,这块不管状态」。
  -->
  <div class="post-body">
    <!-- 行 2:作者行。头像走统一的 InitialAvatar(没图时彩色首字母圆) -->
    <div class="author">
      <InitialAvatar :size="44" :src="post.author?.avatarUrl ? resolveThumb(post.author.avatarUrl) : ''" :seed="post.author?.id" :name="post.author ? displayName(post.author) : ''" />
      <div class="author__main">
        <p class="author__nick">{{ post.author ? displayName(post.author) : '' }}</p>
        <p class="author__time">{{ post.createTime }}</p>
      </div>
      <span v-if="post.status === 0" class="author__status author__status--pending">审核中</span>
      <span v-else-if="post.status === 2" class="author__status author__status--rejected">已驳回</span>
    </div>

    <!-- 行 3:正文。pre-wrap 保留换行,anywhere 防长 URL 撑破(AC-15) -->
    <p class="content" role="article">{{ post.content }}</p>

    <!-- 行 4:话题 chips。超过 5 个合并成「+N」(设计 §3) -->
    <TopicChips :topics="topics" @pick="emit('pick-topic', $event)" />

    <!-- 行 5:媒体区。1 图全宽 / 2 图双列 / ≥3 图三列 -->
    <div v-if="mediaList.length" class="media-grid" :class="`media-grid--${cols}`">
      <template v-for="(m, i) in mediaList" :key="i">
        <!--
          图片加载失败兜底（2026-10-21 加）。
          帖子的 media 记录可能指向已被删除的磁盘文件（实测 post 168/169 就是：
          upload_file 有记录、文件已不在），el-image 会渲染一个破图占位 ——
          在信息流里非常扎眼。这里接 @error 把它换成一个中性的「图片已失效」块，
          高度与图片位一致，不破坏卡片节奏。
        -->
        <div v-if="m.type === 'img' && brokenMedia.has(i)" class="media-img media-img--broken">
          <span>图片已失效</span>
        </div>
        <el-image
          v-else-if="m.type === 'img'"
          :src="resolveThumb(m.url)"
          :preview-src-list="imgList"
          :initial-index="i"
          fit="cover"
          class="media-img"
          :alt="`帖子附图 ${imgIndex(m)}，共 ${imgList.length} 张`"
          @error="onMediaError(i)"
        />
        <video v-else :src="resolveUrl(m.url)" controls class="media-video"></video>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 媒体解析(几个图排几列)由**父组件**算好后传进来,不自己调 usePostMedia:
 * 解析规则是数据问题(契约里 media 字段的结构),不是展示问题,
 * 放这里会让「数据规则」散到视图层。
 */
import { ref } from 'vue'
import { displayName } from '@/utils/mask'
import { resolveUrl, resolveThumb } from '@/utils/resolve'
import InitialAvatar from '@/components/common/InitialAvatar.vue'
import TopicChips from '@/components/post/TopicChips.vue'

/**
 * 加载失败的图片下标集合。
 *
 * 为什么按「下标」记而不是改 mediaList：mediaList 是父组件传进来的 props，
 * 改它属于跨层改数据；而「这张图坏了」纯粹是展示层事实，留在这里最合适。
 * 用 Set 而不是数组：判断是 O(1)，模板里每次渲染都会查。
 */
const brokenMedia = ref(new Set())

function onMediaError(i) {
  // 触发响应式：Set 的增删要让 Vue 感知，必须整体替换
  const next = new Set(brokenMedia.value)
  next.add(i)
  brokenMedia.value = next
}

defineProps({
  post: { type: Object, required: true },
  topics: { type: Array, default: () => [] },
  /** 已解析的媒体列表 */
  mediaList: { type: Array, default: () => [] },
  /** 图片 URL 列表,给 el-image 的预览用(视频不进预览列表) */
  imgList: { type: Array, default: () => [] },
  /**
   * 网格列数语义:one / two / three(usePostMedia 的 mediaCols 返回值)。
   * 早前误声明成 Number,传入 'three' 会触发「Expected Number, got String」
   * 的 Vue 类型告警(值仍能拼进 media-grid--three 类名,不影响渲染,但告警常驻)。
   */
  cols: { type: String, default: 'one' },
  /** 图片在 mediaList 里的真实下标 —— 预览列表只含图片,不等于 i */
  imgIndex: { type: Function, required: true },
})

const emit = defineEmits(['pick-topic'])
</script>

<style scoped src="./PostBody.css"></style>