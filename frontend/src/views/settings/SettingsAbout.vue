<template>
  <div class="sub-inner">
    <!-- 品牌块:logo + 名称 + 版本 + slogan -->
    <el-card class="sgroup">
      <div class="brand">
        <span class="brand__logo">友</span>
        <span class="brand__name">友声</span>
        <span class="brand__ver">校园生活社区 · v1.0.0</span>
      </div>
      <p class="slogan">嘤其鸣矣,求其友声<br>——《诗经 · 小雅 · 伐木》</p>
    </el-card>

    <!--
      联系我们:点击复制邮箱(剪贴板),比跳 mailto 在桌面/移动两端体验都稳。
      用户协议/隐私政策等有真实文案后再加行,不放「筹备中」的空壳。
    -->
    <el-card class="sgroup">
      <button type="button" class="srow" @click="copyEmail">
        <span class="sicon"><el-icon :size="18"><Promotion /></el-icon></span>
        <span class="stext">
          <span class="slabel">联系我们</span>
          <span class="sdesc">{{ CONTACT_EMAIL }}(点击复制)</span>
        </span>
        <el-icon class="schev"><ArrowRight /></el-icon>
      </button>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 关于我们(/settings/about)。
 * 纯静态信息页;版本号与 package.json 的 version 同步维护(改版时一起改)。
 */
import { ArrowRight, Promotion } from '@element-plus/icons-vue'
import { ElMessage } from '@/utils/feedback'

const CONTACT_EMAIL = 'system@yousheng8.cn'

async function copyEmail() {
  try {
    await navigator.clipboard.writeText(CONTACT_EMAIL)
    ElMessage.success('邮箱已复制')
  } catch {
    // 剪贴板不可用(非 https / 旧浏览器)就直接展示,不弹错
    ElMessage.info(`联系邮箱:${CONTACT_EMAIL}`)
  }
}
</script>

<style scoped src="./SettingsSub.css"></style>
