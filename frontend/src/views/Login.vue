<template>
  <!-- 账号被冻结/注销:整页替换(设计 §3 权限态) -->
  <AuthBlocked
    v-if="blocked"
    :title="blocked.title"
    :reason="blocked.reason"
    @back="backFromBlocked"
  />

  <div v-else class="auth-page">
    <div v-if="mode === 'login'" class="auth-card">
      <!--
        品牌区承担了「登录」这个标题的角色,表单顶部不再放一个大标题 ——
        设计 §1.1 明确:再来一个标题是重复。
      -->
      <div class="auth-brand">
        <p class="auth-brand__name">友声</p>
        <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
      </div>

      <!-- 自绘分段:el-tabs 的下划线与内边距是 EP 默认值,和设计 Token 对不上 -->
      <div class="auth-seg" role="tablist" aria-label="登录方式">
        <button
          v-for="t in TABS"
          :key="t.v"
          type="button"
          role="tab"
          class="auth-seg__item"
          :class="{ 'auth-seg__item--on': tab === t.v }"
          :aria-selected="tab === t.v"
          @click="switchTab(t.v)"
        >
          {{ t.l }}
        </button>
      </div>

      <!--
        v-show 保留已填内容(设计 §3 空态):切换 Tab 不该清空邮箱。
        两个表单都常驻,登录失败后切到另一 Tab 也不丢输入。
      -->
      <PasswordForm
        v-show="tab === 'password'"
        :model="pwdForm"
        :loading="loadingPwd"
        @submit="submitPassword"
      />
      <CodeForm
        v-show="tab === 'code'"
        :model="codeForm"
        :loading="loadingCode"
        :cd="cd"
        :sending="sending"
        @submit="submitCode"
        @send-code="sendCode"
      />

      <!--
        表单级错误。设计 §3 明确「不要弹 ElMessage」——
        弹窗会挡住键盘,用户还得再点一次才能改。贴在按钮下方。
      -->
      <AuthErrorLine
        v-if="formError"
        :message="formError"
        :retryable="retryable"
        @retry="retry"
      />

      <div class="auth-link">
        没有账号？
        <router-link to="/login/register">去注册</router-link>
        <span class="auth-link__sep">·</span>
        <button type="button" class="link-btn" @click="mode = 'forgot'">忘记密码？</button>
      </div>
    </div>

    <!-- 找回密码:独立态,整页替换登录卡片内容 -->
    <div v-else class="auth-card">
      <div class="auth-brand">
        <p class="auth-brand__name">友声</p>
        <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
      </div>
      <ForgotPasswordForm @done="onForgotDone" @back="mode = 'login'" />
    </div>
  </div>
</template>

<script setup>
/**
 * 登录页(Login.vue.md)
 *
 * 两个 Tab 切换时**保留已填内容**(设计 §3 空态):切换 Tab 不该清空邮箱。
 *
 * 这个文件现在只做编排:结构、Tab 状态、两个表单模型。
 * 副作用(发请求 + 错误分类)在 useLoginSubmit,倒计时在 useCodeCountdown,
 * 三个状态各自独立成块 —— 因为原本 297 行已顶到 300 行门禁。
 */
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useLoginSubmit, sendCodeFailMessage } from '@/composables/useLoginSubmit'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import AuthBlocked from '@/components/auth/AuthBlocked.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'
import PasswordForm from '@/components/auth/PasswordForm.vue'
import CodeForm from '@/components/auth/CodeForm.vue'
import ForgotPasswordForm from '@/components/auth/ForgotPasswordForm.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const TABS = [
  { v: 'password', l: '学号/邮箱登录' },
  { v: 'code', l: '邮箱验证码登录' },
]

/**
 * 登录卡片 vs 找回密码卡片的切换。
 * 'login' 是默认主态;点「忘记密码？」切到 'forgot'，整页替换登录卡片内容。
 */
const mode = ref('login')
const tab = ref('password')
const pwdForm = reactive({ username: '', password: '', rememberMe: false })
const codeForm = reactive({ email: '', code: '' })

function goHome() {
  const returnUrl = route.query.returnUrl || '/'
  router.push(typeof returnUrl === 'string' ? returnUrl : '/')
}

const {
  blocked,
  formError,
  retryable,
  loadingPwd,
  loadingCode,
  submitPassword,
  submitCode,
  retry,
  clearError,
  backFromBlocked,
} = useLoginSubmit({ pwdForm, codeForm, userStore, onSuccess: goHome })

const { cd, sending, start: startCd } = useCodeCountdown()

function switchTab(v) {
  tab.value = v
  // 切 Tab 清错误:密码错的提示留到验证码登录页会误导
  clearError()
}

/** 找回密码成功:回到登录态,默认落在密码 Tab(重置后需重新登录) */
function onForgotDone() {
  mode.value = 'login'
  tab.value = 'password'
  clearError()
}

/**
 * 发验证码。
 *
 * 失败文案由 sendCodeFailMessage 分类(网络 / 限频 / 上限 / 其他),
 * 不再靠 request.js 的 toast 兜 —— toast 会挡住用户正在点的输入框。
 */
async function sendCode() {
  if (!codeForm.email || !codeForm.email.includes('@')) {
    formError.value = '先填一个合法邮箱，再点获取验证码'
    return
  }
  clearError()
  sending.value = true
  try {
    await sendCodeApi(codeForm.email, 'login')
    ElMessage.success('验证码已发送（开发模式见后端控制台）')
    startCd()
  } catch (e) {
    formError.value = sendCodeFailMessage(e)
  } finally {
    sending.value = false
  }
}
</script>

<style scoped src="./Login.css"></style>

<style scoped>
/* 分段控件。指示条用 transform 过渡,不动 width(设计 §4) */
.auth-seg {
  display: flex;
  gap: var(--ys-space-1);
  margin-bottom: var(--ys-space-6);
  border-bottom: 1px solid var(--ys-border);
}

.auth-seg__item {
  position: relative;
  flex: 1;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-2);
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  color: var(--ys-muted);
  cursor: pointer;
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}

.auth-seg__item--on {
  color: var(--ys-accent);
  font-weight: var(--ys-weight-medium);
}

/* 选中指示条:2px accent,只在选中项下出现 */
.auth-seg__item--on::after {
  content: '';
  position: absolute;
  right: var(--ys-space-2);
  bottom: -1px;
  left: var(--ys-space-2);
  height: 2px;
  background: var(--ys-accent);
}

.auth-seg__item:active {
  opacity: 0.7;
}

/* 表单级错误:13px danger。贴按钮下方,role=alert 出现即播报 */
.auth-error {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
  overflow-wrap: anywhere;
}
</style>
