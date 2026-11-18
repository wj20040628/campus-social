<template>
  <!-- 2026-11-14：注册入口跟着提交按钮走，不再单独占一行 -->
  <router-link class="auth-register-link" to="/login/register">
    还没有账号？<span class="auth-register-link__act">去注册</span>
  </router-link>
</template>

<style scoped>
.auth-register-link {
  flex: none;
  display: inline-flex;
  align-items: center;
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-1);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
  text-decoration: none;
  white-space: nowrap;
}

.auth-register-link__act {
  margin-left: 2px;
  color: var(--ys-accent);
  font-weight: 600;
}

.auth-register-link:hover .auth-register-link__act {
  color: var(--ys-accent-hover);
  text-decoration: underline;
}
</style>