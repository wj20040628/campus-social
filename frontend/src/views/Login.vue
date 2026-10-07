<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <template #header>
        <div class="auth-title">
          <el-icon class="auth-logo"><ChatDotRound /></el-icon>
          <span>校园社交</span>
        </div>
      </template>

      <el-tabs v-model="tab">
        <el-tab-pane label="密码登录" name="password" />
        <el-tab-pane label="邮箱验证码" name="code" />
      </el-tabs>

      <!-- 密码登录 -->
      <el-form v-if="tab === 'password'" :model="pwdForm" :rules="pwdRules" ref="pwdRef" @submit.prevent="loginPassword">
        <el-form-item label="邮箱/手机号" prop="username">
          <el-input v-model="pwdForm.username" placeholder="you@qq.com 或 13800000000" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="pwdForm.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" style="width:100%" :loading="loadingPwd">
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 邮箱验证码登录 -->
      <el-form v-else :model="codeForm" :rules="codeRules" ref="codeRef" @submit.prevent="loginCode">
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="codeForm.email" placeholder="you@qq.com" />
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
          <el-button type="primary" native-type="submit" style="width:100%" :loading="loadingCode">
            验证码登录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="auth-link">
        没有账号？
        <router-link to="/login/register">去注册</router-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const tab = ref('password')

// 密码登录
const pwdRef = ref()
const loadingPwd = ref(false)
const pwdForm = reactive({ username: '', password: '' })
const pwdRules = {
  username: [{ required: true, message: '请输入邮箱或手机号', trigger: 'blur' }],
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

async function loginPassword() {
  await pwdRef.value.validate()
  loadingPwd.value = true
  try {
    await userStore.login(pwdForm.username, pwdForm.password)
    await userStore.fetchMe()
    ElMessage.success('登录成功')
    goHome()
  } catch {
    // request.js 已弹错
  } finally {
    loadingPwd.value = false
  }
}

async function sendCode() {
  if (!codeForm.email || !codeForm.email.includes('@')) {
    ElMessage.warning('先填合法邮箱')
    return
  }
  sending.value = true
  try {
    await sendCodeApi(codeForm.email, 'login')
    ElMessage.success('验证码已发送(开发模式见后端控制台)')
    cd.value = 60
    cdTimer = setInterval(() => {
      cd.value--
      if (cd.value <= 0) clearInterval(cdTimer)
    }, 1000)
  } catch {
    // request.js 已弹错
  } finally {
    sending.value = false
  }
}

async function loginCode() {
  await codeRef.value.validate()
  loadingCode.value = true
  try {
    await userStore.loginByCode(codeForm.email, codeForm.code)
    await userStore.fetchMe()
    ElMessage.success('登录成功')
    goHome()
  } catch {
    // request.js 已弹错
  } finally {
    loadingCode.value = false
  }
}

onUnmounted(() => {
  if (cdTimer) clearInterval(cdTimer)
})
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--app-bg, #f7f8fa) 55%, var(--el-color-primary-light-9));
}
.auth-card {
  width: 400px;
  border-radius: var(--card-radius);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}
.auth-title {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  text-align: center;
  color: var(--el-color-primary);
}
.auth-logo {
  font-size: 24px;
}
.auth-link {
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.auth-link a {
  color: var(--el-color-primary);
  text-decoration: none;
}
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.code-row .el-input {
  flex: 1;
}
.code-row .el-button {
  white-space: nowrap;
  min-width: 96px;
}
</style>
