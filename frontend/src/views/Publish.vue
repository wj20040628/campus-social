<template>
  <div class="pub">
    <!-- 行 1:顶栏。编辑模式的「取消」就是这里的返回(Publish.vue.md §1) -->
    <header class="pub__bar">
      <button type="button" class="pub__back" aria-label="返回" @click="goBack">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <h1 class="pub__title">{{ isEdit ? '编辑动态' : '发动态' }}</h1>
    </header>

    <!-- 编辑模式读取失败:整页替换而不是空编辑器(Publish.vue.md §3) -->
    <StateBlock
      v-if="loadFailed"
      class="pub__state"
      variant="error"
      title="这篇帖子读不出来了"
      description="可能已经被删掉，或者网络断了"
      action-label="回上一页"
      :icon="WarningFilled"
      @action="goBack"
    />

    <!--
      未登录:不跳转、不隐藏,把正文区换成说明块。
      直接跳走会让用户以为产品坏了;给「为什么 + 怎么做」才是转化(设计 §3)。

      判据是**登录**不是认证:发帖不要求校内认证(AC-11 只约束搭话与发布公告),
      所以这里拦住的是「没登录」的人,不是「没认证」的人。
      路由层 requiresAuth 已经挡了绝大多数未登录访问,这个块是兜底 ——
      比如 token 在进页面后过期,userStore 被 clear 掉。
    -->
    <StateBlock
      v-else-if="!canPublish"
      class="pub__state"
      variant="denied"
      title="登录之后才能发动态"
      description="用邮箱或手机号登录，几秒就好"
      action-label="去登录"
      :icon="Lock"
      @action="goLogin"
    />

    <!-- 读取骨架:形状对齐编辑器,不让用户盯着一句「加载中」(Publish.vue.md §3) -->
    <div v-else-if="loading" class="pub__loading" aria-hidden="true">
      <SkeletonList variant="panel" :count="5" />
    </div>

    <template v-else>
      <main class="pub__body">
        <!--
          正文 + 字数做成一张纸卡（2026-10-21）。
          移动端此前没有卡片，裸文本铺在暖米底上，整屏看不出「写到哪」；
          字数器又飘在正文与话题之间。现在两端统一是一张纸。
        -->
        <div class="pub__paper">
          <!--
            可见 label，不靠 placeholder 当 label(Publish.vue.md §5)。
            16px 是 iOS Safari 不自动缩放的下限，14px 聚焦时页面会被放大且不缩回。
          -->
          <label class="pub__label" for="pub-content">正文</label>
          <el-input
            id="pub-content"
            v-model="content"
            type="textarea"
            class="pub__editor"
            placeholder="今天在校园里遇到什么了？"
            maxlength="5000"
            resize="none"
          />
          <!-- 行 6：字数条。紧跟正文，在纸卡内 -->
          <p class="pub__count" :class="{ 'pub__count--warn': nearLimit, 'pub__count--over': atLimit }">
            {{ content.length }} / {{ MAX_LEN }}
          </p>
        </div>
        <p v-if="atLimit" class="pub__count-tip" role="status">
          到 {{ MAX_LEN }} 字了，删一点再发
        </p>

        <!-- 媒体预览：在纸卡外，与正文区同宽 -->
        <MediaGrid :cells="cells" @remove="removeMedia" @retry="retryMedia" />

        <div class="pub__label-group">
          <label class="pub__label" for="pub-topic">话题</label>
        </div>
        <el-input
          id="pub-topic"
          v-model="topicText"
          placeholder="话题（可选，用逗号分隔，如：校园生活,美食）"
          clearable
          class="pub__topic"
        />
        <ul v-if="topicTags.length" class="pub__chips">
          <li v-for="t in topicTags" :key="t">#{{ t }}</li>
          <!-- 超过 5 个只留前 5 个,多的用「+N」提示还有多少 -->
          <li v-if="topicOverflow > 0" class="pub__chips-more">+{{ topicOverflow }}</li>
        </ul>
        <!--
          超长话题提示：后端会静默丢弃 >15 字的话题，不提示的话
          用户会「以为加上了、发出去却没有」，且查不到任何报错。
        -->
        <p v-if="tooLongTopics.length" class="pub__topic-tip" role="status">
          {{ tooLongTopics.length }} 个话题超过 {{ TOPIC_NAME_MAX }} 字，已忽略：
          <span class="pub__topic-tip-name">{{ tooLongTopics.join('、') }}</span>
        </p>
      </main>

      <!-- 错误提示贴操作栏上方。**正文与图片全部保留**(Publish.vue.md §3) -->
      <p v-if="submitError" class="pub__err" role="alert">{{ submitError }}</p>

      <!-- 行 7:吸底操作栏。键盘弹起时随 --kb-h 上移 -->
      <footer class="pub__foot">
        <!--
          视频格式提示（2026-10-21 从常驻整行改成按需展开，与圈子发帖一致）。
          它原来是一整行 10.5px 灰字常驻在吸底栏里 —— 而吸底栏是屏幕上最显眼的位置，
          却用它讲一个 99% 的用户不需要的规则（真要传视频的人才会遇到编码/体积限制）。
          现在只留一个「? 视频格式说明」，点开才展开。
        -->
        <p v-if="videoEnabled" class="pub__hint">
          <button
            type="button"
            class="pub__hint-btn"
            :aria-expanded="showVideoHint"
            @click="showVideoHint = !showVideoHint"
          >
            <span class="pub__hint-q">?</span>
            视频格式说明
          </button>
          <span v-if="showVideoHint" class="pub__hint-text">
            支持 {{ VIDEO_SUPPORTED_FORMATS }}，单条 ≤ {{ maxVideoMB }}MB；{{ VIDEO_UNSUPPORTED_NOTE }}
          </span>
        </p>
        <el-upload
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="uploadFile"
          accept="image/*"
          class="pub__upload"
        >
          <button type="button" class="pub__icon-btn" aria-label="添加图片">
            <el-icon :size="20"><Picture /></el-icon>
          </button>
        </el-upload>
        <el-upload
          v-if="videoEnabled"
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="uploadFile"
          accept="video/*"
          class="pub__upload"
        >
          <button type="button" class="pub__icon-btn" aria-label="添加视频">
            <el-icon :size="20"><VideoCamera /></el-icon>
          </button>
        </el-upload>

        <button
          type="button"
          class="pub__submit"
          :disabled="!canSubmit"
          @click="submit"
        >
          {{ posting ? '提交中' : isEdit ? '保存' : '发布' }}
        </button>
      </footer>
      <SafeArea position="bottom" />
    </template>
  </div>
</template>

<script setup>
/**
 * 发帖 / 编辑动态(Publish.vue.md)
 *
 * 移动端关键结构:正文区 flex:1 撑满,媒体预览与吸底栏不随正文滚动 ——
 * 这样在任何手机高度下,用户都能同时看到「我写了什么」和「发布按钮在哪」。
 */
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { createPost, updatePost, detail } from '@/api/feed'
import { useUserStore } from '@/stores/user'
import { useMediaUpload, parseTopics, droppedTopics, TOPIC_NAME_MAX } from '@/composables/useMediaUpload'
import { VIDEO_SUPPORTED_FORMATS, VIDEO_UNSUPPORTED_NOTE } from '@/utils/videoCodec'
import { useKeyboardViewport } from '@/composables/useKeyboardViewport'
import { markFeedDirty } from '@/composables/useFeedRefresh'
import StateBlock from '@/components/common/StateBlock.vue'
import SkeletonList from '@/components/common/SkeletonList.vue'
import SafeArea from '@/components/mobile/SafeArea.vue'
import MediaGrid from '@/components/post/MediaGrid.vue'
import { ArrowLeft, Lock, Picture, VideoCamera, WarningFilled } from '@element-plus/icons-vue'

const MAX_LEN = 5000
/** 距上限多少字开始变色。设计要求 >4800 预警 */
const WARN_AT = 4800
const MAX_TOPIC_SHOWN = 5

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const content = ref('')
const topicText = ref('')
const posting = ref(false)
const submitError = ref('')
const loading = ref(false)
const loadFailed = ref(false)
const editingId = ref(null)

const {
  cells, failedCount, readyMedia,
  beforeUpload, uploadFile, retry: retryMedia, remove: removeMedia, seed, reset,
  videoEnabled, maxVideoBytes,
} = useMediaUpload()

/** 视频单条上限(MB),供提示文案用。响应式:探测回来后随后端配置更新 */
const maxVideoMB = computed(() => Math.round(maxVideoBytes.value / 1024 / 1024))

/**
 * 视频格式说明是否展开（2026-10-21 改成按需展开）。
 * 默认收起：这段限制只在真要传视频时才需要，常驻在吸底栏是噪音。
 */
const showVideoHint = ref(false)

// 键盘弹起时把 --kb-h 写到 :root,吸底栏靠 translateY 让位(ChatThread 同方案)
useKeyboardViewport()

const isEdit = computed(() => route.name === 'postEdit')

/**
 * 发帖只要求**登录**,不要求**已认证**。
 *
 * [为什么不按 isIdentityVerified 判]
 * AC-11 只约束「发起搭话」与「发布公告」两项,**不含用户发帖**;
 * 后端 PostController.create 也没有挂 @RequireVerified —— 两边本来就该一致。
 * 用 isIdentityVerified 会拦下未认证用户,造成「前端不让发但后端让发」,
 * 而且用户看不到任何错误码,只会觉得莫名其妙。
 *
 * [为什么不写 verified]
 * 后端 UserVO **没有** verified 字段(只有 RecommendUserVO 有)。
 * 写 `userInfo?.verified !== false` 会得到第二个恒真判据 ——
 * 上一版就是这个问题:判据看着有、实际永远放行,AC-11 拦截形同虚设。
 * 后端若日后真给 UserVO 加了 verified,应改的是判据取值,
 * 不是把「已登录」退回成「已认证」。
 */
const canPublish = computed(() => !!userStore.hasToken)

const topicTags = computed(() => parseTopics(topicText.value))
/* 「+N」与 chips 同一套规则(parseTopics 去重后数全量),不用原始分隔数 */
const topicOverflow = computed(() => Math.max(0, parseTopics(topicText.value, Infinity).length - MAX_TOPIC_SHOWN))
/**
 * 因超长被丢弃的话题。
 *
 * 后端（PostTopicBinder）对 >15 字的话题是**静默丢弃**——不报错、不落库。
 * 前端若也不提示，用户输了一个 20 字的话题会看到：chips 里没有它、
 * 发出去之后也没有它，全程零反馈。这类「以为成功了的失败」最难自查，
 * 所以在这里显式列出来告诉用户。
 */
const tooLongTopics = computed(() => droppedTopics(topicText.value))

const nearLimit = computed(() => content.value.length > WARN_AT)
const atLimit = computed(() => content.value.length >= MAX_LEN)

const canSubmit = computed(
  () => !posting.value && content.value.trim().length > 0 && failedCount.value === 0,
)

/** 新建/编辑都从 ?topic= 预填话题;编辑模式预填该帖的话题名 */
async function loadPreload() {
  if (!isEdit.value) {
    const t = route.query.topic
    if (t) topicText.value = String(t)
    return
  }
  loading.value = true
  loadFailed.value = false
  try {
    const data = await detail(route.params.id)
    content.value = data.content || ''
    topicText.value = (data.topics || []).join(',')
    let m = data.media
    if (m && typeof m === 'string') {
      try {
        m = JSON.parse(m)
      } catch {
        m = null
      }
    }
    seed(m)
    editingId.value = data.id
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

// 切 id 或切模式(同组件复用)时全量重载;新建/编辑模式互不残留
watch(
  [() => route.params.id, () => route.name],
  () => {
    content.value = ''
    topicText.value = ''
    editingId.value = null
    submitError.value = ''
    reset()
    loadPreload()
  },
  { immediate: true },
)

async function submit() {
  if (!canSubmit.value) return
  posting.value = true
  submitError.value = ''
  const media = readyMedia.value.length ? JSON.stringify(readyMedia.value) : null
  const topics = topicTags.value.length ? topicTags.value : null
  try {
    if (isEdit.value) {
      const data = await updatePost(editingId.value, { content: content.value, media, topics })
      ElMessage.success(data.status === 0 ? '已保存，命中敏感词重回审核' : '已保存')
      markFeedDirty() // 编辑会改内容/话题,首页列表缓存需重拉
      router.push(`/post/${data.id}`)
    } else {
      await createPost({ content: content.value, media, topics })
      // AC-06:命中敏感词时后端给 status=0(审核中),要明确告诉用户「发出去了但还在审」
      ElMessage.success('发出去了，等别人来搭话吧')
      markFeedDirty() // 新帖必须出现在首页,keep-alive 缓存不能拦着
      router.push('/')
    }
  } catch {
    // 正文与图片**全部保留**,只给一句可重试的提示
    submitError.value = '没发出去，再点一次'
  } finally {
    posting.value = false
  }
}

function goBack() {
  router.back()
}

/**
 * 未登录时的主行动出口。
 *
 * 去登录而不是去认证:能走到这里说明是「没登录」而不是「没认证」
 * (判据见 canPublish)。跳 /onboarding 会让用户以为要先做引导才能发帖。
 *
 * 带 returnUrl:登录后要回到当前这篇草稿,否则用户填到一半的内容
 * 在路由跳转后就找不回来了。
 */
function goLogin() {
  router.push({ path: '/login', query: { returnUrl: route.fullPath } })
}
</script>

<style scoped src="./Publish.css"></style>