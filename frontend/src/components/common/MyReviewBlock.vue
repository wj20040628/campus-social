<template>
  <!--
    作者侧:「我的审核记录」折叠区块(Profile.vue.md §1.3)。
    抽成独立组件的原因:它有 30 多行模板 + 一组 AI 文案映射函数,
    塞在 Profile.vue 里会把主页顶到 300 行门禁之上。

    默认全收起(设计 §1.3):一屏展开十几条审核记录会盖住下面的动态列表。
  -->
  <section v-if="list.length" class="my-review">
    <h2 class="my-review__title">
      我的审核记录
      <span class="my-review__count">共 {{ list.length }} 条</span>
    </h2>

    <el-collapse v-model="active">
      <el-collapse-item v-for="r in list" :key="r.postId" :name="r.postId">
        <template #title>
          <span class="mr-line">
            <span class="mr-status" :class="`mr-status--${r.status}`">
              {{ STATUS_TEXT[r.status] || '未知' }}
            </span>
            <span class="mr-time">{{ r.createTime }}</span>
          </span>
        </template>

        <div class="mr-body">
          <!--
            命中词用中性色不用红(设计 §1.3):命中词是给用户看的提示,
            红色会让用户以为自己违规了,而它其实只是「这几个词被机审注意到了」。
          -->
          <div v-if="r.hitWords?.length" class="mr-row">
            <span class="mr-label">命中词</span>
            <span class="mr-chips">
              <span v-for="w in r.hitWords" :key="w" class="mr-chip">{{ w }}</span>
            </span>
          </div>

          <div v-if="r.reason" class="mr-row">
            <span class="mr-label">驳回理由</span>
            <span class="mr-text">{{ r.reason }}</span>
          </div>

          <div v-if="r.aiVerdict" class="mr-row">
            <span class="mr-label">AI 机审</span>
            <span class="mr-text">{{ aiText(r.aiVerdict) }}</span>
            <span v-if="r.aiReason" class="mr-text">{{ r.aiReason }}</span>
          </div>

          <p v-if="r.status === 0" class="mr-tip">
            内容审核中，通过后即可展示。若长时间未通过，可修改命中词后重新提交。
          </p>
        </div>
      </el-collapse-item>
    </el-collapse>
  </section>
</template>

<script setup>
/**
 * 展开项用 ref 而非普通数组 —— el-collapse 的 v-model 改的是数组本身,
 * 普通数组也能工作,但组件要按响应式语义读它,ref 更明确。
 *
 * [这一行 import 是补的,不是原本就有的]
 * 原文件整个 <script setup> 里没有从 'vue' import 任何东西,却在这里用了
 * `ref([])` —— 运行时报 `ReferenceError: ref is not defined`。
 *
 * ## 后果:个人主页白屏,且刷新无效
 *
 * 实测(Chrome headless + CDP,登录态,直接进 /user/2):
 *   ReferenceError: ref is not defined
 *     at setup (src/components/common/MyReviewBlock.vue:24:16)
 *     at callWithErrorHandling (...) at setupStatefulComponent (...)
 * #main-content 量到 kids=2 text=0 —— 有壳但没内容。
 *
 * 配合 MainLayout 的 <Transition mode="out-in">:组件在 setup 抛错后,
 * 旧组件的 leave 阶段收不到 transitionend,**新组件永不挂载**,
 * 整个 router-view 区域永久空白,点侧栏也恢复不了,只有 F5 能重置。
 *
 * ## 为什么编译期不报
 *
 * check-sfc.mjs 走的是 @vue/compiler-sfc 的 compileScript,它只做语法转换,
 * 不做作用域分析 —— `ref` 被当成普通全局标识符,语法合法。
 * 门禁「全部通过」在这里等于没查。
 */
import { ref } from 'vue'

/** 审核状态码与文案。与后端 review_task.status 一致:0 审核中 1 已通过 2 已驳回 */
const STATUS_TEXT = { 0: '审核中', 1: '已通过', 2: '已驳回' }

/** AI 判定枚举(与 AdminReview 页共用同一套文案,避免两页说法不一致) */
const AI_TEXT = { approve: '建议通过', reject: '建议驳回', manual: '转人工' }

const props = defineProps({
  list: { type: Array, default: () => [] },
})

/**
 * 展开项。放在子组件里而不是父组件:
 * 父组件不需要知道「默认收起」这件事,它只管数据。
 */
const active = ref([])

function aiText(v) {
  return AI_TEXT[v] || v || ''
}
</script>

<style scoped>
.my-review {
  margin: 0 0 var(--ys-space-4);
  padding: var(--ys-space-3);
  background: var(--ys-surface-2);
  border-radius: var(--ys-radius-lg);
}

.my-review__title {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
}

.my-review__count {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-regular);
  color: var(--ys-muted);
}

.mr-line {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  flex: 1;
}

/* 状态标签:文字本身就是状态名,颜色只是辅助(色觉障碍适配) */
.mr-status {
  padding: 0 var(--ys-space-2);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
}

.mr-status--0 {
  background: var(--ys-warning-soft);
  color: var(--ys-warning);
}

.mr-status--1 {
  background: var(--ys-success-soft);
  color: var(--ys-success);
}

.mr-status--2 {
  background: var(--ys-danger-soft);
  color: var(--ys-danger);
}

.mr-time {
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
  font-family: var(--ys-font-mono);
}

.mr-body {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.mr-row {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
}

.mr-label {
  flex: 0 0 56px;
  color: var(--ys-muted);
  font-size: var(--ys-text-xs);
}

.mr-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-1);
}

/* 命中词 chip:中性底。理由见模板注释 */
.mr-chip {
  padding: 0 var(--ys-space-2);
  background: var(--ys-surface);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-text-xs);
  color: var(--ys-fg-2);
}

.mr-text {
  color: var(--ys-fg-2);
  overflow-wrap: anywhere;
}

.mr-tip {
  margin: 0;
  padding: var(--ys-space-2);
  background: var(--ys-warning-soft);
  border-radius: var(--ys-radius-sm);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-warning);
}
</style>