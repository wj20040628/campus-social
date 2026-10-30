<template>
  <div class="sub-inner">
    <!-- 发布信誉卡(2026-10-24):信誉分/账龄/违规清单,放最上——它是这张页的「为什么」。
         加载中/失败由卡片内部自隐藏,这里无条件挂载。 -->
    <TrustStatusCard />

    <el-card class="sgroup">
      <!-- 密码:已设密码凭原密码修改;自动建号(无密码)凭邮箱验证码首次设置 -->
      <button type="button" class="srow" @click="pwdOpen = true">
        <span class="sicon"><el-icon :size="18"><Lock /></el-icon></span>
        <span class="stext">
          <span class="slabel">{{ passwordSet === false ? '设置密码' : '修改密码' }}</span>
          <span class="sdesc">{{ passwordSet === false ? '当前账号还没有密码，用邮箱验证码设置' : '定期更换密码更安全' }}</span>
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
    <ChangePasswordDialog
      v-model="pwdOpen"
      :mode="passwordSet === false ? 'set' : 'change'"
      :email="email"
      @saved="passwordSet = true"
    />
  </div>
</template>

<script setup>
/**
 * 账号与安全(/settings/security)。
 * - 发布信誉:TrustStatusCard,信誉分/账龄/违规清单(拉取成功才渲染)
 * - 绑定邮箱:就地弹 ProfileEmailDialog(与编辑资料页同一个组件),不再跳 /profile
 * - 密码:已设密码走 PUT /user/password(原密码校验);
 *   邮箱验证码自动建号(无密码)走 POST /user/password/send-code + /set
 * - 邮箱是否已绑定:/user/me.email 非空即视为已绑(与编辑资料页同判据)
 */
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Lock, Message } from '@element-plus/icons-vue'
import { me as meApi } from '@/api/auth'
import ProfileEmailDialog from './ProfileEmailDialog.vue'
import ChangePasswordDialog from './ChangePasswordDialog.vue'
import TrustStatusCard from '@/components/common/TrustStatusCard.vue'

const email = ref('')
const emailOpen = ref(false)
const pwdOpen = ref(false)
/** null=还没拉到 /user/me;false=无密码账号(设置密码),true=已有密码(修改密码) */
const passwordSet = ref(null)

/** /user/me 返回的就是脱敏值(maskUsername),非空即已绑定 */
const emailBound = computed(() => !!email.value)

function onEmailSaved(vo) {
  if (vo?.email) email.value = vo.email
}

onMounted(() => {
  meApi()
    .then((me) => {
      email.value = me?.email || ''
      passwordSet.value = me?.passwordSet !== false
    })
    .catch(() => {})
})
</script>

<style scoped src="./SettingsSub.css"></style>
