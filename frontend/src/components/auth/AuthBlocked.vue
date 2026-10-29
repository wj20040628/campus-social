<template>
  <!--
    账号不可用状态。设计 §3 权限态要求「整页替换」而不是弹个 toast:
    冻结的账号再输多少次密码都不会成功,继续展示输入框只会让人反复试。
  -->
  <div class="auth-page">
    <div class="auth-card auth-card--blocked">
      <div class="auth-brand">
        <p class="auth-brand__name">友声</p>
        <p class="auth-brand__slogan">嘤其鸣矣，求其友声</p>
      </div>

      <div class="blocked" role="alert">
        <el-icon class="blocked__icon" :size="28" aria-hidden="true"><Lock /></el-icon>
        <p class="blocked__title">{{ title }}</p>
        <p class="blocked__reason">{{ reason }}</p>
        <!-- 后端补充信息（2026-10-28）：封禁截止时间等。没有就不占位。 -->
        <p v-if="detail" class="blocked__detail">{{ detail }}</p>
      </div>

      <!--
        「返回登录」是设计 §3 点名的次按钮。账号冻结时这个页面就是登录页,
        所以它其实是「回到能输密码的状态」——仍给出路,不把用户堵死。
      -->
      <div class="blocked__actions">
        <el-button class="auth-submit" @click="$emit('back')">返回登录</el-button>
        <!--
          申诉出口（2026-10-26 补）：设计 §3 副句承诺「联系管理员申诉」，
          但此前没有任何可点的东西——承诺了出口而出口是死的。
          仅在冻结(封禁)态出现；注销是用户自己的选择，没有申诉语义。
        -->
        <el-button v-if="appealable" class="auth-submit auth-submit--ghost" @click="$emit('appeal')">提交封禁申诉</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 账号封禁 / 注销的整页状态。
 *
 * 纯展示:标题与副句由父级从 utils/authError.accountBlocked() 拿,
 * 本组件不碰 API、不判断错误码。
 *
 * 副句必须写明下一步(申诉或重新注册)。这是设计 §3 的硬要求,
 * 也因为只说「被封禁」而不给出路,用户除了放弃没有第二个选项。
 */
import { Lock } from '@element-plus/icons-vue'

const props = defineProps({
  /** 主句,如「这个账号被暂停使用了」 */
  title: { type: String, required: true },
  /** 副句:说清能做什么 */
  reason: { type: String, required: true },
  /** 服务端补充说明（如封禁截止时间）；无则不渲染 */
  detail: { type: String, default: '' },
  /** 是否提供申诉出口（封禁=true，注销=false） */
  appealable: { type: Boolean, default: false },
})

defineEmits(['back', 'appeal'])
void props
</script>

<style scoped src="./AuthBlocked.css"></style>
