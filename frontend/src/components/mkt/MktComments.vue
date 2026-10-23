<template>
  <!--
    集市活动留言区（2026-10-21 重做手机端输入方式）。

    【改动原因】改版前是「常驻 textarea + 提交按钮 + 列表」，手机上有三个问题：
      1. 占屏：textarea 至少 90px，而多数访客只看不留言
      2. 键盘遮挡：点进输入框后键盘弹起，输入框可能正好在键盘下方
      3. 误触：滚动时手指碰到 textarea 会直接弹键盘，打断浏览

    现在分端：
      手机（<1024px）：「写留言」按钮 → 底部抽屉，抽屉跟着键盘上移
      桌面（≥1024px）：保留常驻输入框（能边读留言边写，更顺手）

    列表结构两端一致：昵称 / 内容 / 时间 / 举报。
    顶部那条橙色隐私警示也去掉了 —— 只在写留言的输入框下方给一行小字。
  -->
  <section class="mkc">
    <!-- 标题行：条数 + 写留言入口（手机端） -->
    <div class="mkc__head">
      <h2 class="mkc__title">留言</h2>
      <span v-if="list.length" class="mkc__count">{{ list.length }} 条</span>
      <button
        v-if="isMobile"
        type="button"
        class="mkc__write"
        @click="openSheet"
      >
        {{ list.length ? '写留言' : '写第一条' }}
      </button>
    </div>

    <!-- 桌面端：常驻输入框 -->
    <div v-if="!isMobile" class="mkc__composer">
      <textarea
        v-model="draft"
        class="mkc__input"
        rows="2"
        maxlength="500"
        placeholder="说点什么,例如「我有空,一起!」"
      />
      <div class="mkc__composer-foot">
        <span class="mkc__privacy">请勿留手机号、微信号</span>
        <button
          type="button"
          class="mkc__submit"
          :disabled="submitting || !draft.trim()"
          @click="submit"
        >
          {{ submitting ? '发中…' : '留言' }}
        </button>
      </div>
      <p v-if="submitError" class="mkc__err" role="alert">{{ submitError }}</p>
    </div>

    <!-- 列表 / 空态 -->
    <div v-if="loading && !list.length" class="mkc__loading" aria-hidden="true">
      <MktCommentSkeleton v-for="n in 3" :key="n" />
    </div>

    <template v-else-if="list.length">
      <ul class="mkc__list">
        <li v-for="c in list" :key="c.id" class="mkc__item">
          <el-avatar :size="30" :src="resolveThumb(c.avatarUrl)" />
          <div class="mkc__body">
            <p class="mkc__nick">{{ displayNameOf(c) }}</p>
            <p class="mkc__text">{{ c.content }}</p>
            <div class="mkc__meta">
              <span class="mkc__time">{{ fmtTime(c.createTime) }}</span>
              <button
                type="button"
                class="mkc__report"
                aria-label="举报这条留言"
                @click="$emit('report-comment', c)"
              >
                举报
              </button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <!-- 空态：不用 StateBlock 的大图标版式，它太占高度；这里用一行小字 + 手机端有按钮 -->
    <p v-else class="mkc__empty">还没有留言 · 来做第一个</p>

    <!-- 手机端写留言抽屉 -->
    <el-drawer
      v-model="sheetOpen"
      direction="btt"
      size="auto"
      :with-header="false"
      class="mkc-sheet"
    >
      <div class="mkc__sheet">
        <div class="mkc__sheet-head">
          <span class="mkc__sheet-title">写留言</span>
          <button type="button" class="mkc__sheet-close" aria-label="关闭" @click="closeSheet">
            <el-icon :size="18"><Close /></el-icon>
          </button>
        </div>

        <textarea
          ref="sheetInput"
          v-model="draft"
          class="mkc__sheet-input"
          rows="4"
          maxlength="500"
          placeholder="说点什么,例如「我有空,一起!」"
        />

        <div class="mkc__sheet-foot">
          <span class="mkc__privacy">{{ draft.length }} / 500</span>
          <button
            type="button"
            class="mkc__sheet-send"
            :disabled="submitting || !draft.trim()"
            @click="submit"
          >
            {{ submitting ? '发中…' : '发出去' }}
          </button>
        </div>
        <p class="mkc__sheet-tip">请勿留手机号、微信号等隐私信息</p>
        <p v-if="submitError" class="mkc__err" role="alert">{{ submitError }}</p>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
/**
 * MktComments.vue —— 集市活动留言区
 *
 * 扁平列表(无树/无回复):计划 §详情页第 8 段,留言只走「输入+列表」,
 * 不像 PostDetail 的 PostComments 建三级森林。每条右下角带「举报」。
 *
 * 数据契约 MktCommentVO:{ id, activityId, userId, nickname, avatarUrl,
 * content, createTime }。displayName 优先 nickname,空则匿名。
 *
 * 【2026-10-21 分端输入】手机用「写留言」按钮 + 底部抽屉，桌面保留常驻输入框。
 * 失败一律保留草稿（用户写了字不该因为一次提交失败就丢）。
 */
import { ref, nextTick } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from '@/utils/feedback'
import { Close } from '@element-plus/icons-vue'
import { displayName } from '@/utils/mask'
import { resolveThumb } from '@/utils/resolve'
import { useIsMobile } from '@/composables/useBreakpoint'
import MktCommentSkeleton from './MktCommentSkeleton.vue'

const props = defineProps({
  /** 已加载的留言列表(页面通过游标追加后整份回传) */
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  myId: { type: [Number, String], default: null },
})

const emit = defineEmits(['submit', 'report-comment'])

/** 手机端走抽屉；桌面保留常驻输入框（断点与全站一致，来自 design-tokens） */
const isMobile = useIsMobile()

const draft = ref('')
const submitting = ref(false)
const submitError = ref('')
const sheetOpen = ref(false)
const sheetInput = ref(null)

function openSheet() {
  sheetOpen.value = true
  /*
   * 等抽屉动画结束再聚焦。iOS 上过早聚焦会让键盘在抽屉还没就位时弹起，
   * 结果输入框被顶上屏幕外。
   */
  nextTick(() => setTimeout(() => sheetInput.value?.focus(), 260))
}

function closeSheet() {
  sheetOpen.value = false
  // 不主动清空 draft：误触关闭时草稿还在，重新打开能接着写
}

/** MktCommentVO 只有 nickname,displayName 兜底:无昵称显示「匿名」 */
function displayNameOf(c) {
  return c.nickname || displayName({ nickname: c.nickname, username: '' }) || '匿名'
}

function fmtTime(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

/**
 * 发留言：提交给页面，页面走 mkt.mktComment(限流+敏感词)。失败保留草稿。
 *
 * emit 本身不返回 Promise，所以这里 await 的其实是 undefined ——
 * 真正的错误由父组件的 submit 同步抛出（父组件 onCommentSubmit 里 await 接口，
 * 出错会冒泡到这里）。保留 await 是为了兼容将来 emit 变成异步的写法。
 */
async function submit() {
  const text = draft.value.trim()
  if (!text || submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    await emit('submit', text)
    draft.value = ''
    sheetOpen.value = false
    ElMessage.success('留言发出去了')
  } catch (e) {
    // 敏感词命中等后端业务错误:e.message 是后端文案
    submitError.value = e?.message || '没发出去,再点一次留言'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped src="./MktComments.css"></style>
