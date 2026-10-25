<template>
  <!--
    被举报对象信息区(2026-10-24 从 ReportPane 拆出,那里超 400 行门禁)。

    回答管理员的三个问题——举报的是什么类型、谁发的、内容是什么:
    - 类型标签:帖子/评论/私聊/集市,没有它,"666"两个字的摘要等于盲判
    - 作者行:可点跳主页,处置的核心依据是「谁在发」
    - 内容:帖子/集市可点进详情看完整上下文;评论/私聊没有宿主 id 可跳,保持纯文本
  -->
  <div class="rti">
    <p v-if="report.targetAuthorId" class="rti__author">
      {{ report.targetType === 1 ? '被举报人' : '被举报内容的作者' }}:
      <button
        type="button"
        class="rti__link"
        @click="router.push(`/user/${report.targetAuthorId}`)"
      >
        {{ report.targetAuthorNickname || `用户#${report.targetAuthorId}` }}
      </button>
    </p>

    <h3 class="rti__label">
      {{ report.targetType === 1 ? '被举报人' : '被举报内容' }}
      <span class="rti__type" :data-type="report.targetType">
        {{ targetTypeLabel(report.targetType) }}
      </span>
    </h3>

    <button
      v-if="route"
      type="button"
      class="rti__content rti__content--link"
      :class="{ 'is-open': expanded }"
      @click="router.push(route)"
    >
      {{ report.targetSummary }}
    </button>
    <p v-else class="rti__content" :class="{ 'is-open': expanded }">
      {{ report.targetSummary }}
    </p>

    <!-- 补充说明:举报人填的 reasonText,超 60 字后端已截断并置 truncated -->
    <div v-if="report.reasonText" class="rti__note">
      <p class="rti__note-text">
        {{ report.reasonText }}<span v-if="report.reasonTextTruncated">（后端已截断）</span>
      </p>
    </div>
  </div>
</template>

<script setup>
/**
 * props.report = ReportVO(管理端举报行)。
 * expanded(是否展开全文)由父组件持有——吸底栏的「展开/收起」按钮在 ReportPane,
 * 状态跨组件不如留在外层。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { targetTypeLabel, TARGET_TYPE_ROUTE } from '@/constants/reportReason'

const props = defineProps({
  report: { type: Object, required: true },
  expanded: { type: Boolean, default: false },
})

const router = useRouter()

/** 被举报内容可跳转的详情路由;评论/私聊/搭子没有详情页,返回 null 保持纯文本 */
const route = computed(() => {
  const prefix = TARGET_TYPE_ROUTE[props.report.targetType]
  return prefix && props.report.targetId != null
    ? `${prefix}${props.report.targetId}`
    : null
})
</script>

<style scoped>
.rti__author {
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.rti__link {
  border: none;
  background: transparent;
  padding: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-accent);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.rti__label {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

/* 类型标签:中性描边,与暖色理由标签区分——类型是「这是什么」,理由是「为什么被报」 */
.rti__type {
  flex: none;
  font-size: var(--ys-text-xs);
  font-weight: 400;
  color: var(--ys-muted);
  border: 1px solid var(--ys-border);
  border-radius: 999px;
  padding: 0 var(--ys-space-2);
  line-height: 18px;
}

.rti__content {
  margin: 0;
  font-size: var(--ys-text-base);
  line-height: 1.7;
  color: var(--ys-fg);
  display: -webkit-box;
  -webkit-line-clamp: 8;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.rti__content.is-open {
  display: block;
}

/* 可跳转的内容摘要:保留正文观感,仅加 hover 反馈表明可点 */
.rti__content--link {
  width: 100%;
  border: none;
  background: transparent;
  padding: 0;
  text-align: left;
  cursor: pointer;
  font-family: var(--ys-font-body);
}

.rti__content--link:hover {
  color: var(--ys-accent);
}

.rti__note {
  margin-top: var(--ys-space-2);
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
}

.rti__note-text {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: 1.7;
  color: var(--ys-muted);
}
</style>
