<template>
  <div class="sub-inner">
    <el-card class="sgroup">
      <!-- 修改密码:登录态凭原密码改密,弹层内完成 -->
      <button type="button" class="srow" @click="pwdOpen = true">
        <span class="sicon"><el-icon :size="18"><Lock /></el-icon></span>
        <span class="stext">
          <span class="slabel">修改密码</span>
          <span class="sdesc">定期更换密码更安全</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>

      <!-- 绑定邮箱:能力就地完成(不再跳编辑资料页),已绑定显示脱敏值 -->
      <button type="button" class="srow" @click="emailOpen = true">
        <span class="sicon"><el-icon :size="18"><Message /></el-icon></span>
        <span class="stext">
          <span class="slabel">{{ emailBound ? email : '绑定邮箱' }}</span>
          <span class="sdesc">{{ emailBound ? '用于找回密码,点击可修改' : '还未绑定邮箱,点击绑定' }}</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>
    </el-card>

    <ProfileEmailDialog v-model="emailOpen" :bound="emailBound" @saved="onEmailSaved" />
    <ChangePasswordDialog v-model="pwdOpen" />
  </div>
</template>

<script setup>
/**
 * 账号与安全(/settings/security)。
 * - 绑定邮箱:就地弹 ProfileEmailDialog(与编辑资料页同一个组件),不再跳 /profile
 * - 修改密码:弹 ChangePasswordDialog,走 PUT /user/password(原密码校验)
 * - 邮箱是否已绑定:/user/me.email 非空即视为已绑(与编辑资料页同判据)
 */
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Lock, Message } from '@element-plus/icons-vue'
import { me as meApi } from '@/api/auth'
import ProfileEmailDialog from './ProfileEmailDialog.vue'
import ChangePasswordDialog from './ChangePasswordDialog.vue'

const email = ref('')
const emailOpen = ref(false)
const pwdOpen = ref(false)

/** /user/me 返回的就是脱敏值(maskUsername),非空即已绑定 */
const emailBound = computed(() => !!email.value)

function onEmailSaved(vo) {
  if (vo?.email) email.value = vo.email
}

onMounted(() => {
  meApi()
    .then((me) => {
      email.value = me?.email || ''
    })
    .catch(() => {})
})
</script>

<style scoped src="./SettingsSub.css"></style>
