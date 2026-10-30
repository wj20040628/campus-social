<template>
  <!-- 账号被冻结/注销:整页替换(设计 §3 权限态) -->
  <AuthBlocked
    v-if="blocked"
    :title="blocked.title"
    :reason="blocked.reason"
    :detail="blocked.detail || ''"
    :appealable="blocked.appealable === true"
    @back="onBackFromBlocked"
    @appeal="appealOpen = true"
  />

  <div v-else class="auth-page">
    <div v-if="mode === 'login'" class="auth-card">
      <!--
        品牌区承担了「登录」这个标题的角色,表单顶部不再放一个大标题 ——
        设计 §1.1 明确:再来一个标题是重复。
      -->
      <div class="auth-brand">
        <!-- 印鉴：与注册页同一套（见 Auth.css 的 .auth-brand__seal） -->
        <div class="auth-brand__seal" aria-hidden="true">友</div>
        <div class="auth-brand__txt">
          <p class="auth-brand__name">友声</p>
          <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
        </div>
        <!-- 游客登录(2026-10-30):不建账号、不发 token，只读浏览；入口收在品牌行右侧 -->
        <button type="button" class="link-btn auth-guest-link" @click="enterGuest">游客登录</button>
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
        @submit="onSubmitCode"
        @send-code="sendCode"
      >
        <!-- 验证码登录兼做注册:勾选协议是这一步的必要条件,只在验证码 Tab 出现 -->
        <template #consent>
          <div v-if="tab === 'code'" class="auth-consent">
            <el-checkbox v-model="codeForm.consent" class="auth-consent__box" />
            <span class="auth-consent__text">
              我已阅读并同意
              <button type="button" class="legal-link" @click="openLegal('user')">《用户协议》</button>
              和
              <button type="button" class="legal-link" @click="openLegal('privacy')">《隐私政策》</button>
            </span>
          </div>
        </template>
      </CodeForm>

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
        <button type="button" class="link-btn" @click="mode = 'forgot'">忘记密码？</button>
        <!--
          常驻申诉入口（2026-10-26，2026-10-27 收成两个字并同行）：
          被冻结的用户登录失败后会看到 AuthBlocked 里的申诉按钮，但如果只是
          听说自己被封、还没尝试登录，这里必须有入口；已登录态中途被封也会被
          request.js 引导回本页。它和「忘记密码？」都是「进不去账号」时的出口，
          放同一行；原来单独一行写「账号被误封？提交申诉」，视觉重量压过了表单，
          也把一个低频入口抬成了主要动作。
        -->
        <span class="auth-link__sep" aria-hidden="true">·</span>
        <button type="button" class="link-btn" @click="appealOpen = true">申诉</button>
      </div>

      <!-- 协议入口(2026-10-20):登录页不强制勾选(注册时已同意),仅提供可点链接直达同一份弹窗 -->
      <div class="auth-legal">
        <button type="button" class="legal-link" @click="openLegal('user')">《用户协议》</button>
        <span class="auth-legal__sep">·</span>
        <button type="button" class="legal-link" @click="openLegal('privacy')">《隐私政策》</button>
      </div>
    </div>

    <!-- 找回密码:独立态,整页替换登录卡片内容 -->
    <div v-else class="auth-card">
      <div class="auth-brand">
        <!-- 印鉴：与注册页同一套（见 Auth.css 的 .auth-brand__seal） -->
        <div class="auth-brand__seal" aria-hidden="true">友</div>
        <div class="auth-brand__txt">
          <p class="auth-brand__name">友声</p>
          <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
        </div>
      </div>
      <ForgotPasswordForm @done="onForgotDone" @back="mode = 'login'" />
    </div>

    <!-- 备案号(2026-10-28):规范要求首页底部居中展示,不放卡片里 -->
    <SiteFooter class="auth-foot" />

    <!-- 协议弹窗:由登录页底部《用户协议》/《隐私政策》链接调起 -->
    <LegalAgreementDialog ref="legalRef" />
  </div>

  <!--
    封禁申诉弹层（2026-10-26 新增）：账号被封禁者无有效登录态，邮箱是唯一触达路径。
    必须挂在模板根部（AuthBlocked 的兄弟节点）：封禁整页态会 v-else 卸掉 auth-page，
    弹层若在 auth-page 内部，blocked=true 时整棵子树被卸载，弹层永远挂不上 ——
    表现为「点申诉没反应」。放在根层级则始终在 DOM，v-model 才能生效。
  -->
  <BanAppealDialog v-model="appealOpen" />
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
import { reactive, ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { AUTH_ERR, blockedByCode } from '@/utils/authError'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { useLoginSubmit, sendCodeFailMessage } from '@/composables/useLoginSubmit'
import { useCodeCountdown } from '@/composables/useCodeCountdown'
import AuthBlocked from '@/components/auth/AuthBlocked.vue'
import AuthErrorLine from '@/components/auth/AuthErrorLine.vue'
import PasswordForm from '@/components/auth/PasswordForm.vue'
import CodeForm from '@/components/auth/CodeForm.vue'
import ForgotPasswordForm from '@/components/auth/ForgotPasswordForm.vue'
import LegalAgreementDialog from '@/components/auth/LegalAgreementDialog.vue'
import BanAppealDialog from '@/components/auth/BanAppealDialog.vue'
import SiteFooter from '@/components/common/SiteFooter.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

// 协议弹窗 ref（2026-10-20）：登录页底部《用户协议》/《隐私政策》链接调起，不强制勾选
const legalRef = ref(null)
function openLegal(doc) { legalRef.value?.open(doc) }

const appealOpen = ref(false)

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
const codeForm = reactive({ email: '', code: '', consent: false })

function goHome() {
  const returnUrl = route.query.returnUrl || '/'
  router.push(typeof returnUrl === 'string' ? returnUrl : '/')
}

/** 游客登录：只读浏览，不创建账号；清掉可能残留的登录态后再进首页 */
function enterGuest() {
  userStore.enterGuest()
  router.push('/')
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

/**
 * 验证码登录提交前先确认协议勾选。
 * 自动建号发生在这条路径上,勾选必须拦在发请求之前,不能只做视觉提示。
 */
function onSubmitCode() {
  if (!codeForm.consent) {
    formError.value = '请先阅读并同意《用户协议》和《隐私政策》'
    return
  }
  submitCode()
}

/**
 * 登录态中途被封（2026-10-26）：request.js 清掉 token 后带 blocked=业务码
 * 跳回登录页，这里直接切到封禁整页态，不让用户再对着一张没用的登录表单。
 */
function applyBlockedQuery() {
  const code = Number(route.query.blocked)
  const msg = typeof route.query.msg === 'string' ? route.query.msg : ''
  const b = blockedByCode(code, msg)
  if (b) blocked.value = { ...b, appealable: code === AUTH_ERR.USER_FROZEN }
}

function onBackFromBlocked() {
  backFromBlocked()
  if (route.query.blocked) {
    const q = { ...route.query }
    delete q.blocked
    router.replace({ path: route.path, query: q })
  }
}

onMounted(applyBlockedQuery)
watch(() => route.query.blocked, applyBlockedQuery)

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
    await sendCodeApi(codeForm.email, 'login', { silent: true })
    ElMessage.success('验证码已发送，请查收邮箱（含垃圾箱）')
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
/* 验证码 Tab 的协议勾选:一行放下,窄屏自动折行 */
.auth-consent {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-1);
  margin: calc(-1 * var(--ys-space-2)) 0 var(--ys-space-4);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.auth-consent__box {
  flex: none;
  height: auto;
}

.auth-consent__text {
  padding-top: 2px;
}

/* 「忘记密码？· 申诉」同行时的分隔符,与底部协议行的分隔符同一观感 */
.auth-link__sep {
  margin: 0 var(--ys-space-1);
  color: var(--ys-meta);
}

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
