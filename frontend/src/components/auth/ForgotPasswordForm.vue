<template>
  <el-form
    ref="formRef"
    :model="form"
    :rules="rules"
    label-position="top"
    @submit.prevent="onSubmit"
  >
    <p class="fp-tip">通过学号找回密码,重置验证码会发到你绑定的安全凭证邮箱。</p>

    <el-form-item label="学号" prop="studentNo">
      <el-input
        v-model="form.studentNo"
        placeholder="输入你的学号"
        maxlength="32"
        autocomplete="username"
      />
    </el-form-item>

    <!-- 已发送:展开验证码 + 新密码 -->
    <template v-if="sent">
      <el-form-item label="邮箱验证码" prop="code">
        <el-input
          v-model="form.code"
          maxlength="6"
          placeholder="6 位验证码"
          inputmode="numeric"
        />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <PasswordInput
          v-model="form.newPassword"
          placeholder="6~64 位"
          autocomplete="new-password"
        />
      </el-form-item>
    </template>

    <el-form-item>
      <el-button
        v-if="!sent"
        type="primary"
        native-type="button"
        class="auth-submit"
        :loading="sending"
        @click="send"
      >
        发送验证码
      </el-button>
      <el-button
        v-else
        type="primary"
        native-type="submit"
        class="auth-submit"
        :loading="loading"
      >
        重置密码
      </el-button>
    </el-form-item>

    <p v-if="formError" class="field-error" role="alert">{{ formError }}</p>

    <div class="auth-link">
      <button type="button" class="link-btn" @click="$emit('back')">返回登录</button>
    </div>
  </el-form>
</template>

<script setup>
/**
 * 找回密码表单(账号体系重构)。
 *
 * 两步:① 提交学号 → 后端向绑定的安全凭证邮箱发重置验证码;
 *       ② 填邮箱验证码 + 新密码 → 重置并踢下线其他设备。
 * 不返回 token(重置后需重新登录,更安全),成功 emit('done')。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { forgotPassword, resetPassword } from '@/api/auth'
import { isNetworkFail, authErrCode, AUTH_ERR, credentialMessage } from '@/utils/authError'
import PasswordInput from './PasswordInput.vue'

const emit = defineEmits(['done', 'back'])

const formRef = ref()
const form = reactive({ studentNo: '', code: '', newPassword: '' })
const sent = ref(false)
const sending = ref(false)
const loading = ref(false)
const formError = ref('')

const rules = {
  studentNo: [{ required: true, message: '请输入学号', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码要 6 位以上', trigger: 'blur' },
  ],
}

async function send() {
  formError.value = ''
  const ok = await formRef.value.validateField('studentNo').catch(() => false)
  if (!ok) return
  sending.value = true
  try {
    await forgotPassword(form.studentNo.trim())
    sent.value = true
    ElMessage.success('重置验证码已发送到你的安全凭证邮箱')
  } catch (e) {
    if (authErrCode(e) === AUTH_ERR.EMAIL_NOT_BOUND) {
      formError.value = '这个学号还没绑定邮箱,无法用邮箱找回,请先去绑定邮箱'
      return
    }
    formError.value = isNetworkFail(e) ? '网络好像断了,稍后再试' : '发送失败,稍后再试'
  } finally {
    sending.value = false
  }
}

async function onSubmit() {
  formError.value = ''
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  loading.value = true
  try {
    await resetPassword(form.studentNo.trim(), form.code, form.newPassword)
    ElMessage.success('密码已重置,请用新密码登录')
    emit('done')
  } catch (e) {
    if (authErrCode(e) === AUTH_ERR.CODE_INVALID) {
      formError.value = '验证码不对或者过期了,重新获取一次'
      return
    }
    // 复用凭据类文案兜底
    formError.value = credentialMessage(e, 'password') || '重置失败,再试一次'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.fp-tip {
  margin: 0 0 var(--ys-space-4);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}

.field-error {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
  overflow-wrap: anywhere;
}

.auth-link {
  text-align: center;
}

.link-btn {
  border: 0;
  background: none;
  color: var(--ys-accent);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  font-family: var(--ys-font-body);
}
</style>
