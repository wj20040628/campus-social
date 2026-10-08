<template>
  <el-input
    :model-value="modelValue"
    type="password"
    show-password
    :placeholder="placeholder"
    :autocomplete="autocomplete"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <!--
      设计 §5:密码框眼睛按钮要有 aria-label="显示密码" / "隐藏密码"。

      [为什么用 slot 而不是事后找 DOM 补属性]
      Element Plus 把切换逻辑挂在一个**没有 role 的 span** 上
      (input.vue: onClick=handlePasswordVisible, class="el-input__icon el-input__password"),
      既不可聚焦也没有无障碍语义。
      事后 querySelector 补 aria-label 只能解决「读屏念不出」,
      解决不了「键盘 Tab 根本跳不到这个按钮」。

      在 slot 里放一个真 <button>:点击冒泡到 EP 的 span 上 → 切换照常;
      同时它自身可聚焦、Enter/Space 触发 click、读屏能念出 aria-label。
      一举两得,且不 patch 第三方 DOM。
    -->
    <template #password-icon="{ visible }">
      <button
        type="button"
        class="pwd-eye"
        :aria-label="visible ? '隐藏密码' : '显示密码'"
        :aria-pressed="visible"
        tabindex="0"
      >
        <el-icon aria-hidden="true">
          <component :is="visible ? Hide : View" />
        </el-icon>
      </button>
    </template>
  </el-input>
</template>

<script setup>
/**
 * 带无障碍标签的密码输入框。
 *
 * Login 与 Register 各有一处 show-password 输入框,设计 §5 对两页是同一条要求。
 * 收在这里而不是各写一遍 slot,是因为这类补丁一旦只改一处就会漂移,
 * 而它漂移的方式是**静默的**(页面照常工作,只是读屏用户少一个信息)。
 */
import { View, Hide } from '@element-plus/icons-vue'

defineProps({
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: undefined },
  autocomplete: { type: String, default: undefined },
})

defineEmits(['update:modelValue'])
</script>

<style scoped>
/*
  撑满 EP 给的 icon 容器。EP 的 .el-input__icon 是 25×25 的小方块,
  设计 §5 要求热区 44×44 —— 这里把可点区域撑到 --ys-touch-min。
  视觉上仍是那只眼睛,不额外画任何东西。
*/
.pwd-eye {
  display: flex;
  align-items: center;
  justify-content: center;
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  /* 负 margin 抵消 EP 给的 5px 内缩,让热区与输入框右缘对齐 */
  margin: 0 calc(var(--ys-space-1) * -1);
  padding: 0;
  border: 0;
  background: none;
  color: var(--ys-muted);
  cursor: pointer;
  /* 触控目标与 EP 图标的尺寸不一致,过渡放到颜色上避免位移 */
  transition: color var(--ys-motion-fast) var(--ys-ease-out);
}

.pwd-eye:hover {
  color: var(--ys-fg-2);
}

.pwd-eye:active {
  opacity: 0.7;
}

.pwd-eye:focus-visible {
  outline: 2px solid var(--ys-accent);
  outline-offset: 2px;
  border-radius: var(--ys-radius-sm);
}
</style>
