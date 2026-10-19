<template>
  <div class="sub-inner">
    <el-card class="sgroup">
      <!--
        修改密码:后端改密接口**待做**(PUT /user/password 之类),先置灰给真实预期,
        不放假交互。接口就绪后把 disabled 去掉、补弹层表单即可。
      -->
      <div class="srow srow--static" aria-disabled="true">
        <span class="sicon"><el-icon :size="18"><Lock /></el-icon></span>
        <span class="stext">
          <span class="slabel">修改密码</span>
          <span class="sdesc">即将上线</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </div>
      <!--
        绑定邮箱:能力已有(编辑资料页的安全邮箱弹层,api/auth.bindEmail),
        这里做入口跳转,不重复实现第二份表单。
      -->
      <button type="button" class="srow" @click="router.push('/profile')">
        <span class="sicon"><el-icon :size="18"><Message /></el-icon></span>
        <span class="stext">
          <span class="slabel">{{ emailBound ? '修改绑定邮箱' : '绑定邮箱' }}</span>
          <span class="sdesc">{{ emailBound ? '用于找回密码,点击前往修改' : '用于找回密码,前往编辑资料绑定' }}</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 账号与安全(/settings/security)。
 * - 绑定邮箱:能力已有,入口跳编辑资料页(/profile)复用现有弹层
 * - 修改密码:后端接口待做,先置灰标「即将上线」
 * - 邮箱是否已绑定:/user/me.email 非空即视为已绑(与编辑资料页同判据)
 */
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, Lock, Message } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { me as meApi } from '@/api/auth'

const router = useRouter()
const email = ref('')

const emailBound = computed(() => !!email.value)

onMounted(() => {
  meApi()
    .then((me) => {
      email.value = me?.email || ''
    })
    .catch(() => {})
})
</script>

<style scoped src="./SettingsSub.css"></style>
