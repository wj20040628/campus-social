<template>
  <!--
    修改密码弹层(设置 → 账号与安全)。
    与找回密码不同:这里凭「原密码」证明身份,不需要邮箱验证码。
  -->
  <el-dialog
    :model-value="modelValue"
    title="修改密码"
    :width="isMobile ? '92%' : '440px'"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :model="form" label-position="top" @submit.prevent="submit">
      <el-form-item label="原密码">
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
      <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { changePassword } from '@/api/auth'
import { useIsMobile } from '@/composables/useBreakpoint'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const isMobile = useIsMobile()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const error = ref('')

/** 每次打开清空上一次残留,避免把上次没提交的旧密码/新密码带进来 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.oldPassword = ''
    form.newPassword = ''
    form.confirm = ''
    error.value = ''
  },
)

async function submit() {
  if (!form.oldPassword) {
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
    await changePassword(form.oldPassword, form.newPassword)
    emit('update:modelValue', false)
    emit('saved')
    ElMessage.success('密码已修改')
  } catch (e) {
    // changePassword 走 silent,业务错误不弹全局 toast,由这里就地提示
    error.value = e?.isBusiness ? (e.message || '修改失败') : '网络异常,稍后再试'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.cpd__error {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
  overflow-wrap: anywhere;
}
</style>
