<template>
  <!--
    封禁申诉弹层（2026-10-26 新增）。

    ## 为什么独立成组件而不是写在 Login.vue
    登录页已顶着 300 行门禁；申诉弹层的表单与提交逻辑自成一块，
    塞进 Login.vue 必然破线。它有两个入口（登录页常驻链接、
    封禁整页态的「提交封禁申诉」按钮），独立组件的复用成本最低。

    ## 为什么单独开 /auth/appeal-ban
    被封禁者此刻没有有效登录态，站内通知没有收件人；联系邮箱是管理员
    唯一能主动触达的路径。认证申诉（2026-10-19）已经打通了「公开端点 +
    user_id 置 NULL + feedback 队列 + 邮件触达」这条链，封禁申诉复用它，
    但**不能复用其 DTO**：认证申诉强制学号，而封禁用户可能从没填过学号，
    邮箱才是账号本身。故新开端点，邮箱必填、学号选填。
  -->
  <el-dialog
    :model-value="open"
    title="封禁申诉"
    :width="isMobile ? '92%' : '480px'"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
    @closed="reset"
  >
    <div class="ba">
      <p class="ba__lead">如果你认为封禁有误，留下注册邮箱和申诉说明，管理员会人工复核并通过邮箱联系你。</p>

      <label class="ba__label" for="ba-mail">注册邮箱（必填）</label>
      <el-input id="ba-mail" v-model="email" placeholder="用于登录友声的邮箱" maxlength="100" />

      <label class="ba__label" for="ba-no">学号（选填）</label>
      <el-input id="ba-no" v-model="studentNo" maxlength="14" placeholder="填了能更快定位账号" />

      <label class="ba__label" for="ba-note">补充说明（选填）</label>
      <el-input
        id="ba-note"
        v-model="note"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        resize="none"
        placeholder="说明你认为封禁有误的原因"
      />

      <p v-if="error" class="ba__error" role="alert">{{ error }}</p>
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="submit">提交申诉</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { appealBan } from '@/api/auth'
import { useIsMobile } from '@/composables/useBreakpoint'

const props = defineProps({
  open: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const isMobile = useIsMobile()

const studentNo = ref('')
const email = ref('')
const note = ref('')
const error = ref('')
const saving = ref(false)

/** 邮箱必填；学号选填，填了就必须是 13/14 位数字（与后端 DTO 同口径） */
const canSubmit = computed(() => {
  const no = (studentNo.value || '').trim()
  return (
    /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test((email.value || '').trim()) &&
    (no === '' || /^\d{13,14}$/.test(no))
  )
})

watch(
  () => props.open,
  (v) => {
    if (v) error.value = ''
  },
)

function reset() {
  studentNo.value = ''
  email.value = ''
  note.value = ''
  error.value = ''
}

async function submit() {
  if (!canSubmit.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await appealBan(
      {
        email: email.value.trim(),
        studentNo: studentNo.value.trim(),
        note: note.value.trim(),
      },
      { silent: true },
    )
    ElMessage.success('申诉已提交，管理员会通过邮箱联系你')
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e?.message || '没提交成功，再试一次'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.ba__lead {
  margin: 0 0 var(--ys-space-4);
  font-size: var(--ys-text-sm);
  line-height: 1.7;
  color: var(--ys-muted);
}

.ba__label {
  display: block;
  margin: var(--ys-space-3) 0 var(--ys-space-1);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg-2);
}

.ba__error {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}
</style>
