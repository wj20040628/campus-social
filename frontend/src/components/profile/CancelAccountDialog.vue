<template>
  <!--
    注销账号（2026-10-31，《个人信息保护法》删除权）。
    两步：先发邮箱验证码，再输码确认；验证码发到账号绑定的邮箱，页面只显示打码地址。
    注销不可逆，因此要求勾选确认后才允许提交。
  -->
  <el-dialog
    v-model="visible"
    title="注销账号"
    :width="isMobile ? '94%' : '480px'"
    class="cancel__dialog"
    append-to-body
    @closed="reset"
  >
    <div class="cancel">
      <p class="cancel__warn">
        注销后不可恢复：账号无法再登录，昵称、邮箱、学号、头像、简介和校园信息会被清空。
      </p>
      <p class="cancel__note">
        已发布的帖子、评论会保留并显示为「已注销用户」，不再关联你的个人信息；如需彻底删除，请先在对应页面自行删除。
      </p>

      <div class="cancel__code">
        <el-input
          v-model="code"
          maxlength="6"
          placeholder="6 位验证码"
          inputmode="numeric"
          autocomplete="one-time-code"
        />
        <el-button :disabled="countdown > 0" :loading="sending" @click="send">
          {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
        </el-button>
      </div>
      <p class="cancel__hint">
        验证码将发送到 {{ maskedEmail || '账号绑定邮箱' }}，5 分钟内有效。
      </p>

      <el-checkbox v-model="acked">我已了解注销后无法恢复</el-checkbox>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button
        type="danger"
        :disabled="!canSubmit"
        :loading="submitting"
        @click="submit"
      >确认注销</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { cancelAccount, sendCancelCode } from '@/api/auth'
import { useIsMobile } from '@/composables/useBreakpoint'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const isMobile = useIsMobile()

const visible = ref(false)
const maskedEmail = ref('')
const code = ref('')
const acked = ref(false)
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
let timer = null

const canSubmit = computed(
  () => acked.value && /^\d{6}$/.test(code.value.trim()) && !submitting.value,
)

function open() {
  visible.value = true
}

function reset() {
  code.value = ''
  acked.value = false
  maskedEmail.value = ''
  sending.value = false
  submitting.value = false
  stopCountdown()
}

function stopCountdown() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
  countdown.value = 0
}

async function send() {
  if (countdown.value > 0 || sending.value) return
  sending.value = true
  try {
    maskedEmail.value = await sendCancelCode()
    ElMessage.success('验证码已发送，请查收邮箱')
    countdown.value = 60
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) stopCountdown()
    }, 1000)
  } catch {
    // 全局拦截器已提示（未登录 / 已注销 / 管理员账号 / 未绑邮箱）
  } finally {
    sending.value = false
  }
}

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await cancelAccount(code.value.trim())
    ElMessage.success('账号已注销')
    visible.value = false
    userStore.clear()
    router.replace('/login')
  } catch {
    // 验证码错误等由全局拦截器提示，保留弹窗让用户重试
  } finally {
    submitting.value = false
  }
}

onBeforeUnmount(stopCountdown)

defineExpose({ open })
</script>

<style scoped src="./CancelAccountDialog.css"></style>
