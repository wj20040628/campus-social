<template>
  <!--
    举报操作表(PostDetail.vue.md §1 行 1)
    挂在 PostDetail 的「更多」上。两级:先选原因类型,再选「其他」时补一句说明。
    「其他」这一档是硬要求 —— 举报原因只有 5 类,一定会遇到 5 类都不沾的,
    没有兜底用户就只能乱选一档,管理员拿到的 reasonType 是错的。
  -->
  <ActionSheet :model-value="modelValue" title="举报这篇帖子" @update:model-value="$emit('update:modelValue', $event)">
    <p class="rp__hint">管理员会看到你选的原因。恶意举报会影响你在校园里的信用。</p>

    <button
      v-for="r in REASONS"
      :key="r.v"
      type="button"
      class="asheet__item"
      @click="pick(r)"
    >
      <span class="rp__label">{{ r.label }}</span>
    </button>

    <!-- 选「其他」才展开补充说明。默认不占屏幕高度 -->
    <div v-if="picked === 5" class="rp__more">
      <label class="rp__more-label" for="rp-text">补充一下(选填)</label>
      <el-input
        id="rp-text"
        v-model="reasonText"
        type="textarea"
        :rows="3"
        :maxlength="200"
        show-word-limit
        placeholder="哪里不合适,管理员会看到这段"
      />
      <button
        type="button"
        class="asheet__item rp__submit"
        :disabled="submitting"
        @click="submit"
      >
        {{ submitting ? '提交中' : '提交举报' }}
      </button>
    </div>

    <p v-if="error" class="rp__error" role="alert">{{ error }}</p>
  </ActionSheet>
</template>

<script setup>
/**
 * 举报提交。契约 openapi §ReportSubmitRequest:
 * { targetType, targetId, reasonType, reasonText? }
 *
 * 端点是**幂等**的:重复举报同一目标返回 duplicated=true 而不是报错,
 * 所以提交成功的提示要区分「已记录」与「已受理」,不然用户会以为没生效而反复点。
 */
import { ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import ActionSheet from '@/components/mobile/ActionSheet.vue'

/** 目标类型:1用户 2帖子 3评论 4聊天消息 5搭子 */
const TARGET = { USER: 1, POST: 2, COMMENT: 3, MESSAGE: 4, PARTNER: 5 }

/** 原因类型。与 openapi §ReportSubmitRequest.reasonType 的 enum 逐项对应 */
const REASONS = [
  { v: 1, label: '骚扰' },
  { v: 2, label: '广告' },
  { v: 3, label: '色情' },
  { v: 4, label: '诈骗' },
  { v: 5, label: '其他' },
]

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 被举报对象 id */
  postId: { type: [Number, String], default: null },
  /** 注入的提交函数,便于测试与复用 */
  submitFn: { type: Function, required: true },
})

const emit = defineEmits(['update:modelValue', 'done'])

const picked = ref(0)
const reasonText = ref('')
const submitting = ref(false)
const error = ref('')

// 每次打开重置:上一次选到第 3 档,这一次打开还停在第 3 档,用户会误判
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    picked.value = 0
    reasonText.value = ''
    error.value = ''
  },
)

function pick(r) {
  if (r.v === 5) {
    picked.value = 5
    return
  }
  picked.value = r.v
  doSubmit()
}

async function doSubmit() {
  if (submitting.value || !props.postId) return
  submitting.value = true
  error.value = ''
  try {
    const data = await props.submitFn({
      targetType: TARGET.POST,
      targetId: props.postId,
      reasonType: picked.value,
      // 只在有内容时带上,避免提交一个空串让后端的 maxLength 校验白跑一趟
      ...(reasonText.value.trim() ? { reasonText: reasonText.value.trim() } : {}),
    })
    ElMessage.success(data?.duplicated ? '已经举报过了,记录还在' : '举报已受理,管理员会看')
    emit('update:modelValue', false)
    emit('done', data)
  } catch (e) {
    /**
     * 三级兜底,e?.message 不能省。
     *
     * request.js 的业务错误分支(响应 code != 200)抛的是 `new Error(message)`,
     * **不带 response 字段** —— 只写 `e?.response?.data?.message` 的话,
     * 业务错误永远落到兜底文案,用户看到的是「没提交成功」这种没有信息量的话,
     * 而后端其实已经说了原因(比如 1033 举报记录不存在)。
     */
    error.value = e?.response?.data?.message || e?.message || '没提交成功,再点一次'
  } finally {
    submitting.value = false
  }
}

const submit = doSubmit
</script>

<style scoped>
.rp__hint {
  margin: 0;
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-1);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.rp__more {
  padding: var(--ys-space-3) var(--ys-space-4);
  border-top: 1px solid var(--ys-border-soft);
}

.rp__more-label {
  display: block;
  margin-bottom: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
}

.rp__submit {
  margin-top: var(--ys-space-3);
  border: 1px solid var(--ys-danger);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-danger);
  color: #fff;
  justify-content: center;
  font-weight: var(--ys-weight-medium);
}

.rp__submit:disabled {
  opacity: 0.6;
}

.rp__error {
  margin: 0;
  padding: var(--ys-space-2) var(--ys-space-4) var(--ys-space-3);
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}
</style>