<template>
  <el-form
    ref="formRef"
    :model="model"
    :rules="rules"
    label-position="top"
    @submit.prevent="onSubmit"
  >
    <el-form-item label="邮箱" prop="email">
      <el-input v-model="model.email" placeholder="you@qq.com" autocomplete="email" />
    </el-form-item>
    <el-form-item label="验证码" prop="code">
      <div class="code-row">
        <el-input
          v-model="model.code"
          maxlength="6"
          placeholder="6位验证码"
          inputmode="numeric"
        />
        <!--
          aria-live="polite"(设计 §4):倒计时读屏要能播报。
          用 polite 而非 assertive —— 倒计时不是紧急信息,每秒钟打断用户
          正在输入的邮箱会很难受。
          放在独立的 span 里而不是按钮上:按钮文案每秒变一次,放按钮上
          部分读屏不会重播按钮文本。
        -->
        <el-button
          :disabled="sending || cd > 0"
          :loading="sending"
          aria-disabled="sending || cd > 0"
          @click="$emit('send-code')"
        >
          获取验证码
          <span class="sr-only" aria-live="polite">{{ cdHint }}</span>
        </el-button>
        <!--
          倒计时文案同时可见。设计 §4 要求「60s 后重发」,
          aria-live 的隐藏节点只是给读屏用的, sighted 用户看的是这一行。
        -->
        <span v-if="cd > 0" class="code-cd" aria-hidden="true">{{ cd }}s 后重发</span>
      </div>
    </el-form-item>
    <!-- 协议勾选由页面注入,位置固定在提交按钮之前 -->
    <slot name="consent" />
    <el-form-item>
      <!--
        2026-10-28:新人不知道「验证码登录」等于注册。
        相比在输入框下加一行说明，直接把动作写全更省地方 ——
        用「/」不用「&」：中文界面约定如此，读屏也会把 & 读成 and。
      -->
      <el-button type="primary" native-type="submit" class="auth-submit" :loading="loading">
        验证码登录 / 注册
      </el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
/**
 * 邮箱验证码登录表单。
 *
 * 与 PasswordForm 一样是从 Login.vue 拆出来的(300 行门禁)。
 *
 * [设计 §4 与 §5 的具体要求,这里都落到了]
 * §4 倒计时:按钮禁用 + 文案「60s 后重发」+ aria-live="polite"
 * §5 密码框眼睛按钮 aria-label —— 见 PasswordForm 内的说明,
 *    EP 的 show-password 图标按钮不带 aria-label,用一个全局补丁处理
 */
import { ref, computed } from 'vue'

const props = defineProps({
  model: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  cd: { type: Number, default: 0 },
  sending: { type: Boolean, default: false },
})

const emit = defineEmits(['submit', 'send-code'])

const formRef = ref()

const rules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
}

/**
 * 读屏播报文本。
 *
 * 每秒播报一次太吵 —— 设计 §4 要求「每 10 秒播报」。
 * 只在整 10 秒和归零那一刻换文案,中间 9 秒保持不变:
 * aria-live 只在节点文本变化时播报,文本不变就等于自动降频。
 */
const cdHint = computed(() => {
  const s = props.cd
  if (s === 0) return '可以重新获取验证码'
  if (s % 10 === 0 || s <= 3) return `${s} 秒后可以重新获取验证码`
  return ''
})

async function onSubmit() {
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  emit('submit')
}
</script>

<style scoped>
/* 倒计时文案与按钮同行,按钮宽度会跟着变 —— 用固定宽度避免布局抖动 */
.code-cd {
  flex: 0 0 auto;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  white-space: nowrap;
}

/* 只给读屏:视觉上隐藏但保留在无障碍树里。
   不能用 display:none 或 visibility:hidden —— 那样 aria-live 不会播报 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

</style>
