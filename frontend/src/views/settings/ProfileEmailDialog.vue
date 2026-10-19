<template>
  <!--
    绑定 / 修改安全凭证邮箱弹层(从 SettingsProfile.vue 拆出)。
    拆的理由:编辑资料页要按预览稿重排版,主文件已贴 300 行门禁,
    验证码倒计时与错误映射是自成一体的一块,拆出去主页面只留表单骨架。
    成功时把最新的 ofPrivate 抛给父级刷新展示。
  -->
  <el-dialog
    :model-value="modelValue"
    :title="bound ? '修改安全凭证邮箱' : '绑定安全凭证邮箱'"
    :width="isMobile ? '92%' : '480px'"
    append-to-body
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form :model="form" label-position="top" @submit.prevent="submit">
      <el-form-item label="邮箱">
        <el-input v-model="form.email" placeholder="用于找回密码的邮箱" autocomplete="email" />
      </el-form-item>
      <el-form-item label="验证码">
        <div class="ped__code-row">
          <el-input v-model="form.code" maxlength="6" placeholder="6 位验证码" inputmode="numeric" />
          <el-button :disabled="sending || cd > 0" :loading="sending" @click="sendCode">获取验证码</el-button>
        </div>
        <p v-if="cd > 0" class="ped__cd">{{ cd }}s 后重发</p>
      </el-form-item>
      <p v-if="error" class="ped__error" role="alert">{{ error }}</p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="binding" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 安全凭证邮箱绑定弹层。学号是主账号,邮箱只作找回密码 / 绑定的安全凭证。
 * 绑定 / 修改都走 type=bind 验证码:先发码到目标邮箱,再带码调 POST /user/bind-email,
 * 成功后后端踢下线其他设备,并返回 ofPrivate(含 emailBound + 脱敏 email)。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import { authErrCode, AUTH_ERR, isNetworkFail } from '@/utils/authError'
import { sendCodeFailMessage } from '@/composables/useLoginSubmit'
import { useUserStore } from '@/stores/user'
import { useIsMobile } from '@/composables/useBreakpoint'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 已绑定则标题与按钮语义都是「修改」 */
  bound: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue', 'saved'])

const userStore = useUserStore()
const isMobile = useIsMobile()
const form = reactive({ email: '', code: '' })
const sending = ref(false)
const binding = ref(false)
const error = ref('')
const { cd, start: startCd } = useCodeCountdown()

/** 每次打开都清空上一次的残留,避免上一次没填完的邮箱被误提交 */
watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    form.email = ''
    form.code = ''
    error.value = ''
  },
)

async function sendCode() {
  const s = (form.email || '').trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) {
    error.value = '先填一个合法邮箱,再点获取验证码'
    return
  }
  error.value = ''
  sending.value = true
  try {
    await sendCodeApi(s, 'bind')
    ElMessage.success('验证码已发送（开发模式见后端控制台）')
    startCd()
  } catch (e) {
    error.value = sendCodeFailMessage(e)
  } finally {
    sending.value = false
  }
}

async function submit() {
  const s = (form.email || '').trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)) {
    error.value = '邮箱格式不正确'
    return
  }
  if (!/^\d{6}$/.test(form.code || '')) {
    error.value = '请填写 6 位验证码'
    return
  }
  error.value = ''
  binding.value = true
  try {
    const vo = await userStore.bindEmail(s, form.code)
    emit('update:modelValue', false)
    emit('saved', vo)
    ElMessage.success('邮箱已绑定')
  } catch (e) {
    const code = authErrCode(e)
    if (code === AUTH_ERR.EMAIL_ALREADY_BOUND) {
      error.value = '这个邮箱已被其他账号绑定了'
      return
    }
    if (code === AUTH_ERR.CODE_INVALID) {
      error.value = '验证码不对或者过期了,重新获取一次'
      return
    }
    error.value = isNetworkFail(e) ? '网络好像断了,稍后再试' : '绑定失败,稍后再试'
  } finally {
    binding.value = false
  }
}
</script>

<style scoped>
/* 验证码输入框与按钮同行,复用 --ys-* 设计 token */
.ped__code-row {
  display: flex;
  gap: var(--ys-space-2);
  align-items: center;
}

.ped__code-row .el-input {
  flex: 1;
}

.ped__cd {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.ped__error {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
  overflow-wrap: anywhere;
}
</style>
