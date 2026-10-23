<template>
  <el-form
    ref="formRef"
    :model="form"
    :rules="rules"
    label-position="top"
    @submit.prevent="onSubmit"
  >
    <!--
      2026-10-21：从「只收学号」改成「邮箱或学号」。
      注册流程已改为邮箱必填、学号选填 —— 若这里仍只认学号，
      注册时跳过学号的用户永远找不回密码（他连一个能填的值都没有）。
      输入框不做格式限制：含 @ 当邮箱、纯数字当学号，由后端 findByAccount 分流。
    -->
    <p class="fp-tip">输入邮箱或学号，重置验证码会发到该账号绑定的邮箱。</p>

    <el-form-item label="邮箱或学号" prop="account">
      <el-input
        v-model="form.account"
        placeholder="例如 wj@qq.com 或 23080901060536"
        maxlength="128"
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
 * 找回密码表单。
 *
 * 两步：① 提交邮箱或学号 → 后端向该账号绑定的邮箱发重置验证码；
 *       ② 填邮箱验证码 + 新密码 → 重置并踢下线其他设备。
 * 不返回 token（重置后需重新登录，更安全），成功 emit('done')。
 *
 * 【2026-10-21 支持邮箱】字段名从 studentNo 改成 account，
 * 与后端的 ForgotPasswordDTO / ResetPasswordDTO 同名同义。
 */
import { reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { forgotPassword, resetPassword } from '@/api/auth'
import { isNetworkFail, authErrCode, AUTH_ERR, credentialMessage } from '@/utils/authError'
import PasswordInput from './PasswordInput.vue'

const emit = defineEmits(['done', 'back'])

const formRef = ref()
const form = reactive({ account: '', code: '', newPassword: '' })
const sent = ref(false)
const sending = ref(false)
const loading = ref(false)
const formError = ref('')

const rules = {
  account: [{ required: true, message: '请输入邮箱或学号', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码要 6 位以上', trigger: 'blur' },
  ],
}

/**
 * 归一化后再提交。
 *
 * 邮箱必须转小写：库里存的就是小写，不转的话会因大小写不一致「查不到账号」，
 * 表现为静默成功但收不到验证码 —— 这类问题用户无从自查。
 * 判据与后端 findByAccount 一致：含 @ 当邮箱，否则当学号。
 */
function normalizedAccount() {
  const raw = form.account.trim()
  return raw.includes('@') ? raw.toLowerCase() : raw
}

async function send() {
  formError.value = ''
  const ok = await formRef.value.validateField('account').catch(() => false)
  if (!ok) return
  sending.value = true
  try {
    await forgotPassword(normalizedAccount())
    sent.value = true
    ElMessage.success('重置验证码已发送到该账号绑定的邮箱')
  } catch (e) {
    if (authErrCode(e) === AUTH_ERR.EMAIL_NOT_BOUND) {
      /*
       * 走到这里说明该账号没绑邮箱 —— 但**新注册流程不可能出现这种账号**
       * （注册时邮箱是必填并要验证码验证所有权的）。
       * 所以这只会是账号体系重构前留下的历史账号。
       *
       * 因此提示不该再说「先登录去设置里绑」：新用户看到会以为产品有缺陷，
       * 而老用户也确实没法自己解决 —— 他连密码都没了，登不进去。
       * 给一条真实可行的出路：联系管理员。
       */
      formError.value =
        '该账号是早期注册的，尚未绑定邮箱，暂时无法自助找回密码，请联系管理员协助处理'
      return
    }
    formError.value = isNetworkFail(e) ? '网络好像断了，稍后再试' : '发送失败，稍后再试'
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
    /*
     * 第二步必须用与第一步完全相同的标识：后端按它再查一次账号，
     * 再拿该账号的邮箱去验码。传了别的值会变成「码是对的但账号对不上」。
     */
    await resetPassword(normalizedAccount(), form.code, form.newPassword)
    ElMessage.success('密码已重置，请用新密码登录')
    emit('done')
  } catch (e) {
    if (authErrCode(e) === AUTH_ERR.CODE_INVALID) {
      formError.value = '验证码不对或者过期了，重新获取一次'
      return
    }
    // 复用凭据类文案兜底
    formError.value = credentialMessage(e, 'password') || '重置失败，再试一次'
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

/*
 * 「返回登录」的行内文字按钮。
 *
 * 【为什么这里要定义一份】Auth.css 由 Login.css 通过 @import 引入，
 * 而 Login.vue 是 scoped —— 那些规则会被加上 Login 的 data-v 属性，
 * **作用不到本组件的 DOM**。实测「返回登录」因此渲染成浏览器默认的灰框按钮
 * （border: 2px outset，background: rgb(240,240,240)）。
 *
 * 与 Auth.css 里的同名规则保持同样的值。两处各写一份而不是提到全局：
 * .link-btn 只有这两个按钮在用，加进全局样式等于给全站留个语义含糊的口子。
 * 要改外观就两处一起改 —— 这行注释就是那句提醒。
 */
.link-btn {
  border: 0;
  background: none;
  margin: 0;
  padding: 0;
  color: var(--ys-accent);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  font-family: var(--ys-font-body);
  cursor: pointer;
}

.link-btn:hover {
  color: var(--ys-accent-hover);
}
</style>
