<template>
  <el-form
    ref="formRef"
    :model="model"
    :rules="rules"
    label-position="top"
    @submit.prevent="onSubmit"
  >
    <el-form-item label="学号或邮箱" prop="username">
      <el-input
        v-model="model.username"
        placeholder="输入学号,或绑定的邮箱"
        autocomplete="username"
      />
    </el-form-item>
    <el-form-item label="密码" prop="password">
      <PasswordInput
        v-model="model.password"
        autocomplete="current-password"
      />
    </el-form-item>
    <!--
      记住我:勾选 → refresh 7 天,免重复登录;不勾 → 3 天。
      放在密码框与提交按钮之间,视觉上归到「这次登录」这一组。
    -->
    <el-form-item>
      <label class="remember">
        <el-checkbox v-model="model.rememberMe" />
        <span>记住我（7 天内免登录）</span>
      </label>
    </el-form-item>
    <el-form-item>
      <el-button type="primary" native-type="submit" class="auth-submit" :loading="loading">
        登录
      </el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
/**
 * 密码登录表单。
 *
 * 从 Login.vue 拆出来的原因:主文件 297 行、离 300 行门禁只剩 3 行,
 * 而设计 §3 要求补的封禁整页态 + 字段级错误 + 可点重试还要往里加。
 *
 * [模型的归属]
 * model 由父级持有并传进来,不在这里 reactive。
 * 原因:两个 Tab 的模型都要活过一次提交失败(网络失败时「点这里重试」
 * 要用原样内容重发)。模型如果锁在子组件里、父级又要在封禁态之外
 * 保留它,就得靠 ref 往上捞 —— 那条路更难读。传对象引用是这里最直接的做法。
 *
 * v-show 而不是 v-if 由父级负责(在 Login.vue 里,两个表单一起切):
 * 切换 Tab 不能清空已填的邮箱(设计 §3 空态),v-if 会重建表单、丢 DOM 状态。
 */
import { ref } from 'vue'
import PasswordInput from './PasswordInput.vue'

defineProps({
  /** 表单模型。由父级创建并持有 */
  model: { type: Object, required: true },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['submit'])

const formRef = ref()

/** 校验规则。学号(13/14 位纯数字,主账号)或邮箱两种,复刻注册页同一套判据 */
const rules = {
  username: [
    { required: true, message: '请输入学号或邮箱', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        const s = (v || '').trim()
        const isEmail = s.includes('@') && s.split('@').length === 2
        const isStudentNo = /^\d{13,14}$/.test(s)
        if (isEmail || isStudentNo) cb()
        else cb(new Error('需为 13~14 位学号或合法邮箱'))
      },
      trigger: 'blur',
    },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 先过本地校验再 emit:拦在组件内,父级不用重复写 validate */
async function onSubmit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  emit('submit')
}

// 封禁态整页替换后,表单从 v-if 之外重新渲染时残留的红色校验字要清掉,
// 否则用户看到「请输入密码」却没碰过输入框。暴露 clearValidate 让父级调用。
defineExpose({ clearValidate: () => formRef.value?.clearValidate() })
</script>

<style scoped>
/* 记住我:checkbox 与文字同一行,文字用 muted 色,贴近「这次登录」这一组 */
.remember {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
  cursor: pointer;
}

.remember :deep(.el-checkbox) {
  height: auto;
}
</style>
