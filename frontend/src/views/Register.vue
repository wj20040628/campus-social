<template>
  <div class="auth-page">
    <el-card class="auth-card">
      <template #header>
        <div class="auth-title">
          <el-icon class="auth-logo"><ChatDotRound /></el-icon>
          <span>校园社交</span>
        </div>
      </template>
      <el-form :model="form" :rules="rules" ref="formRef" @submit.prevent="onSubmit">
        <el-form-item label="邮箱/手机号" prop="username">
          <el-input v-model="form.username" placeholder="you@qq.com 或 13800000000" />
        </el-form-item>
        <el-form-item label="验证码" prop="verifyCode">
          <div class="code-row">
            <el-input v-model="form.verifyCode" maxlength="6" placeholder="6位验证码" />
            <el-button
              :disabled="sending || cd > 0"
              :loading="sending"
              @click="sendCode"
            >
              {{ cd > 0 ? `${cd}s 后重发` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="学号(可选)" prop="studentNo">
          <el-input v-model="form.studentNo" placeholder="如 2024001234" />
        </el-form-item>
        <el-form-item label="昵称(可选)" prop="nickname">
          <el-input v-model="form.nickname" placeholder="不填则用账号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="6~64 位" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit" style="width:100%" :loading="loading">
            注册并登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="auth-link">
        已有账号？
        <router-link to="/login/form">去登录</router-link>
      </div>
      <div class="auth-tip">开发模式:验证码打后端控制台;prod 模式发到填的邮箱</div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { sendCode as sendCodeApi } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const sending = ref(false)
const cd = ref(0)           // 倒计时秒
let cdTimer = null
const form = reactive({
  username: '', password: '', studentNo: '', nickname: '', verifyCode: '',
})

const rules = {
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
  verifyCode: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码 6~64 位', trigger: 'blur' },
  ],
}

async function sendCode() {
  const isEmail = form.username.includes('@') && form.username.split('@').length === 2
  const isPhone = /^1[3-9]\d{9}$/.test(form.username)
  if (!isEmail && !isPhone) {
    ElMessage.warning('先填合法的邮箱或手机号')
    return
  }
  sending.value = true
  try {
    await sendCodeApi(form.username, 'register')
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

async function onSubmit() {
  await formRef.value.validate()
  loading.value = true
  try {
    await userStore.register(
      form.username,
      form.password,
      form.studentNo || undefined,
      form.verifyCode,
      form.nickname || undefined,
    )
    await userStore.fetchMe()
    ElMessage.success('注册成功')
    router.push('/')
  } catch {
    // request.js 已弹错
  } finally {
    loading.value = false
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
  width: 420px;
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
.auth-tip {
  text-align: center;
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 6px;
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
