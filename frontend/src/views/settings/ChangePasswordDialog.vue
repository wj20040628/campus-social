<template>
  <!--
    密码弹层(设置 → 账号与安全)。
    - 已设密码:凭原密码修改(账号体系一直以来的口径)
    - 未设密码(邮箱验证码自动建号):凭绑定邮箱验证码首次设置
  -->
  <el-dialog
    :model-value="modelValue"
    :title="isSet ? '设置密码' : '修改密码'"
    :width="isMobile ? '92%' : '440px'"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :model="form" label-position="top" @submit.prevent="submit">
      <template v-if="isSet">
        <p class="cpd__hint">验证码会发送到你绑定的邮箱{{ email ? `：${email}` : '' }}</p>
        <el-form-item label="邮箱验证码">
          <div class="cpd__code-row">
            <el-input
              v-model="form.code"
              maxlength="6"
              inputmode="numeric"
              placeholder="6位验证码"
              autocomplete="one-time-code"
            />
            <el-button
              :disabled="sending || cd > 0"
              :loading="sending"
              @click="sendCode"
            >
              获取验证码
            </el-button>
          </div>
          <span v-if="cd > 0" class="cpd__cd" aria-live="polite">{{ cd }}s 后重发</span>
        </el-form-item>
      </template>
      <el-form-item v-else label="原密码">
        <el-input
          v-model="form.oldPassword"
          type="password"
          show-password
          maxlength="64"
          autocomplete="current-password"
          placeholder="输入当前密码"
        />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          maxlength="64"
          autocomplete="new-password"
          placeholder="6~64 位"
        />
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="form.confirm"
          type="password"
          show-password
          maxlength="64"
          autocomplete="new-password"
          placeholder="再输入一次新密码"
        />
      </el-form-item>
      <p v-if="error" class="cpd__error" role="alert">{{ error }}</p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ isSet ? '设置密码' : '确定' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { changePassword, sendSetPasswordCode, setPassword } from '@/api/auth'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import { useIsMobile } from '@/composables/useBreakpoint'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** set=未设密码,凭邮箱验证码首次设置;change=已设密码,凭原密码修改 */
  mode: { type: String, default: 'change' },
  /** 脱敏后的绑定邮箱,仅用于弹层内说明验证码发往哪里 */
  email: { type: String, default: '' },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const isMobile = useIsMobile()
const isSet = computed(() => props.mode === 'set')
const form = reactive({ oldPassword: '', code: '', newPassword: '', confirm: '' })
const saving = ref(false)
const error = ref('')
const { cd, sending, start: startCd, stop: stopCd } = useCodeCountdown()

/** 每次打开清空上一次残留,避免把上次没提交的旧密码/验证码带进来 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      stopCd()
      return
    }
    form.oldPassword = ''
    form.code = ''
    form.newPassword = ''
    form.confirm = ''
    error.value = ''
  },
)

async function sendCode() {
  if (sending.value || cd.value > 0) return
  error.value = ''
  sending.value = true
  try {
    await sendSetPasswordCode()
    ElMessage.success('验证码已发送，请查收邮箱（含垃圾箱）')
    startCd()
  } catch (e) {
    error.value = e?.isBusiness ? (e.message || '验证码没发出去') : '验证码没发出去，稍后再试'
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (isSet.value && !/^\d{6}$/.test(form.code)) {
    error.value = '请输入 6 位邮箱验证码'
    return
  }
  if (!isSet.value && !form.oldPassword) {
    error.value = '请输入原密码'
    return
  }
  if (form.newPassword.length < 6 || form.newPassword.length > 64) {
    error.value = '新密码 6~64 位'
    return
  }
  if (form.confirm !== form.newPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }
  error.value = ''
  saving.value = true
  try {
    if (isSet.value) {
      await setPassword(form.code, form.newPassword)
      ElMessage.success('密码已设置，之后可以用邮箱或学号加密码登录')
    } else {
      await changePassword(form.oldPassword, form.newPassword)
      ElMessage.success('密码已修改')
    }
    emit('update:modelValue', false)
    emit('saved')
  } catch (e) {
    // 两个接口都走 silent,业务错误由这里就地提示
    error.value = e?.isBusiness ? (e.message || '操作失败') : '网络异常,稍后再试'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.cpd__hint {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  overflow-wrap: anywhere;
}

.cpd__code-row {
  display: flex;
  gap: var(--ys-space-2);
  width: 100%;
}

.cpd__code-row :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

.cpd__cd {
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.cpd__error {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
  overflow-wrap: anywhere;
}
</style>
