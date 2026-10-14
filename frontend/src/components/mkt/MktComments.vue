<template>
  <!--
    集市活动留言区(计划 §详情页第 8 段)
    ------------------------------------------------------------------
    沟通只走这一条公共留言区:输入框 + 提交 + 扁平列表。
    留言**不带树、不带回复** —— 计划明确「参加走留言区」「不做回复层级」。
    每条留言右下角一个小型「举报」按钮,走既有 POST /report。
    留言区上方固定一条 ⚠️ 隐私提示(计划 §免责声明)。
  -->
  <section class="mkc">
    <!-- 顶部提示:引导性文案(计划 §留言区固定文案) -->
    <p class="mkc__hint">💡有意向?可以在这里留言!例如留言:「我有空,一起!」</p>

    <!-- 隐私警示(计划 §免责声明第 3 处:留言区上方) -->
    <div class="mkc__warn" role="note">
      ⚠️本平台仅提供信息展示,沟通请自行确认安全,请勿在留言区留下手机号、微信号等隐私信息!
    </div>

    <!-- 输入框 + 提交 -->
    <div class="mkc__composer">
      <textarea
        v-model="draft"
        class="mkc__input"
        rows="2"
        maxlength="500"
        placeholder="说点什么,例如「我有空,一起!」"
      />
      <div class="mkc__composer-foot">
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

    <!-- 列表 / 空态(增量④:留言为空给可点出口) -->
    <div v-if="loading && !list.length" class="mkc__loading" aria-hidden="true">
      <MktCommentSkeleton v-for="n in 3" :key="n" />
    </div>

    <template v-else-if="list.length">
      <div class="mkc__head">留言 {{ list.length }}</div>
      <ul class="mkc__list">
        <li v-for="c in list" :key="c.id" class="mkc__item">
          <el-avatar :size="32" :src="resolveUrl(c.avatarUrl)" />
          <div class="mkc__body">
            <p class="mkc__nick">{{ displayNameOf(c) }}</p>
            <p class="mkc__text">{{ c.content }}</p>
            <div class="mkc__meta">
              <span class="mkc__time">{{ fmtTime(c.createTime) }}</span>
              <!-- 每条右下角小型举报(计划 §举报) -->
              <button
                type="button"
                class="mkc__report"
                :aria-label="`举报这条留言`"
                @click="$emit('report-comment', c)"
              >
                举报
              </button>
            </div>
          </div>
        </li>
      </ul>
    </template>

    <StateBlock
      v-else
      variant="empty"
      title="还没有留言"
      description="来做第一个,把意向说清楚"
      :icon="ChatLineSquare"
    />
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
 */
import { ref } from 'vue'
import dayjs from 'dayjs'
import { ElMessage } from '@/utils/feedback'
import { ChatLineSquare } from '@element-plus/icons-vue'
import { resolveUrl } from '@/utils/resolve'
import StateBlock from '@/components/common/StateBlock.vue'
import MktCommentSkeleton from './MktCommentSkeleton.vue'

const props = defineProps({
  /** 已加载的留言列表(页面通过游标追加后整份回传) */
  list: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  myId: { type: [Number, String], default: null },
})

const emit = defineEmits(['submit', 'report-comment'])

const draft = ref('')
const submitting = ref(false)
const submitError = ref('')

/** MktCommentVO 只有 nickname,displayName 兜底:无昵称显示「匿名」 */
function displayNameOf(c) {
  return c.nickname || displayName({ nickname: c.nickname, username: '' }) || '匿名'
}

function fmtTime(t) {
  return t ? dayjs(t).format('MM-DD HH:mm') : ''
}

/** 发留言:提交给页面,页面走 mkt.mktComment(限流+敏感词)。失败保留草稿。 */
async function submit() {
  const text = draft.value.trim()
  if (!text || submitting.value) return
  submitting.value = true
  submitError.value = ''
  try {
    await emit('submit', text)
    draft.value = ''
  } catch (e) {
    // 敏感词命中等后端业务错误:e.message 是后端文案
    submitError.value = e?.message || '没发出去,再点一次留言'
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.mkc {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
}

/* ---------------- 顶部提示 + 隐私警示 ---------------- */
.mkc__hint {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.mkc__warn {
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--ys-warning);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-warning-soft);
  color: var(--ys-warning);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
}

/* ---------------- 输入区 ---------------- */
.mkc__composer {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.mkc__input {
  width: 100%;
  box-sizing: border-box;
  min-height: 56px;
  padding: var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  color: var(--ys-fg);
  resize: vertical;
}

.mkc__input:focus-visible {
  border-color: var(--ys-accent);
  box-shadow: var(--ys-focus-ring);
  outline: none;
}

.mkc__composer-foot {
  display: flex;
  justify-content: flex-end;
}

.mkc__submit {
  min-height: var(--ys-touch-min);
  min-width: var(--ys-touch-min);
  padding: 0 var(--ys-space-5);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  cursor: pointer;
  transition: background-color var(--ys-motion-fast) var(--ys-ease-out);
}

.mkc__submit:hover { background: var(--ys-accent-hover); }

.mkc__submit:disabled {
  border-color: var(--ys-border-control);
  background: var(--ys-surface-2);
  color: var(--ys-meta);
  cursor: not-allowed;
}

/* 留言失败:贴近输入框而不是 toast,草稿保留(与发帖同纪律) */
.mkc__err {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

/* ---------------- 列表 ---------------- */
.mkc__head {
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.mkc__list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
}

.mkc__item {
  display: flex;
  gap: var(--ys-space-3);
}

.mkc__body {
  flex: 1;
  min-width: 0;
}

.mkc__nick {
  margin: 0;
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.mkc__text {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  color: var(--ys-fg);
  word-break: break-word;
}

.mkc__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--ys-space-1);
}

.mkc__time {
  font-family: var(--ys-font-mono);
  font-variant-numeric: tabular-nums;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.mkc__report {
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
  cursor: pointer;
  padding: 2px var(--ys-space-1);
  border-radius: var(--ys-radius-sm);
}

.mkc__report:hover {
  color: var(--ys-danger);
  background: var(--ys-danger-soft);
}

.mkc__loading {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-4);
}
</style>
