<template>
  <div class="auth-page">
    <div class="auth-card">
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

      <!-- 密码登录 -->
      <el-form
        v-show="tab === 'password'"
        ref="pwdRef"
        :model="pwdForm"
        :rules="pwdRules"
        label-position="top"
        @submit.prevent="loginPassword"
      >
        <el-form-item label="邮箱/手机号" prop="username">
          <el-input
            v-model="pwdForm.username"
            placeholder="you@qq.com 或 13800000000"
            autocomplete="username"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="pwdForm.password"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" class="auth-submit" :loading="loadingPwd">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 邮箱验证码登录 -->
      <el-form
        v-show="tab === 'code'"
        ref="codeRef"
        :model="codeForm"
        :rules="codeRules"
        label-position="top"
        @submit.prevent="loginCode"
      >
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="codeForm.email" placeholder="you@qq.com" autocomplete="email" />
        </el-form-item>
        <el-form-item label="验证码" prop="code">
          <div class="code-row">
            <el-input v-model="codeForm.code" maxlength="6" placeholder="6位验证码" />
            <el-button
              :disabled="sending || cd > 0"
              :loading="sending"
              @click="sendCode"
            >
              {{ cd > 0 ? `${cd}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" class="auth-submit" :loading="loadingCode">
            验证码登录
          </el-button>
        </el-form-item>
      </el-form>

      <!--
        表单级错误。设计 §3 明确「不要弹 ElMessage」——
        弹窗会挡住键盘,用户还得再点一次才能改。贴在按钮下方。
      -->
      <p v-if="formError" class="auth-error" role="alert">{{ formError }}</p>

      <div class="auth-link">
        没有账号？
        <router-link to="/login/register">去注册</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 登录页(Login.vue.md)
 *
 * 两个 Tab 切换时**保留已填内容**(设计 §3 空态):切换 Tab 不该清空邮箱。
 * 用 v-show 而不是 v-if 就是为了不丢 DOM 状态 —— v-if 会重建表单,
 * 用户在密码登录填了邮箱,切到验证码登录再切回来就得重填。
 */
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const TABS = [
  { v: 'password', l: '密码登录' },
  { v: 'code', l: '邮箱验证码' },
]

const tab = ref('password')
/** 表单级错误信息。贴在按钮下方而不是弹 toast(设计 §3) */
const formError = ref('')

function switchTab(v) {
  tab.value = v
  formError.value = ''
}

// 密码登录
const pwdRef = ref()
const loadingPwd = ref(false)
const pwdForm = reactive({ username: '', password: '' })
const pwdRules = {
  username: [
    { required: true, message: '请输入邮箱或手机号', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        const isEmail = v.includes('@') && v.split('@').length === 2
        const isPhone = /^1[3-9]\d{9}$/.test(v)
        if (isEmail || isPhone) cb()
        else cb(new Error('需为合法邮箱或 11 位手机号'))
      },
      trigger: 'blur',
    },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

// 验证码登录
const codeRef = ref()
const loadingCode = ref(false)
const sending = ref(false)
const cd = ref(0)
let cdTimer = null
const codeForm = reactive({ email: '', code: '' })
const codeRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
}

function goHome() {
  const returnUrl = route.query.returnUrl || '/'
  router.push(typeof returnUrl === 'string' ? returnUrl : '/')
}

/** 账号密码错误:说「邮箱或密码不对」而不是回显后端的原始 message */
function handleLoginError(e, fallback) {
  formError.value = fallback
  ElMessage.closeAll()
}

async function loginPassword() {
  const ok = await pwdRef.value.validate().catch(() => false)
  if (!ok) return
  formError.value = ''
  loadingPwd.value = true
  try {
    await userStore.login(pwdForm.username, pwdForm.password)
    await userStore.fetchMe()
    goHome()
  } catch (e) {
    handleLoginError(e, '邮箱或密码不对，再试一次')
  } finally {
    loadingPwd.value = false
  }
}

async function sendCode() {
  if (!codeForm.email || !codeForm.email.includes('@')) {
    formError.value = '先填一个合法邮箱，再点获取验证码'
    return
  }
  formError.value = ''
  sending.value = true
  try {
    await sendCodeApi(codeForm.email, 'login')
    ElMessage.success('验证码已发送（开发模式见后端控制台）')
    cd.value = 60
    cdTimer = setInterval(() => {
      cd.value--
      if (cd.value <= 0) clearInterval(cdTimer)
    }, 1000)
  } catch {
    formError.value = '验证码没发出去，稍后再试一次'
  } finally {
    sending.value = false
  }
}

async function loginCode() {
  const ok = await codeRef.value.validate().catch(() => false)
  if (!ok) return
  formError.value = ''
  loadingCode.value = true
  try {
    await userStore.loginByCode(codeForm.email, codeForm.code)
    await userStore.fetchMe()
    goHome()
  } catch (e) {
    handleLoginError(e, '验证码不对或者过期了，重新获取一次')
  } finally {
    loadingCode.value = false
  }
}

onUnmounted(() => {
  if (cdTimer) clearInterval(cdTimer)
})
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