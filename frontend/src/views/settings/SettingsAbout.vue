<template>
  <div class="sub-inner">
    <!-- 品牌块:logo + 名称 + 版本 + slogan -->
    <el-card class="sgroup">
      <div class="brand">
        <span class="brand__logo">友</span>
        <span class="brand__name">友声</span>
        <span class="brand__ver">校园生活社区 · v{{ APP_VERSION }}</span>
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

    <!--
      更新日志:数据在 src/constants/changelog.js,版本号读 package.json。
      发版只改「package.json 的 version + changelog 最前面加一条」,这里零改动。
    -->
    <el-card class="sgroup">
      <div class="log">
        <p class="log__caption">更新日志</p>
        <div v-for="rel in CHANGELOG" :key="rel.version" class="log__rel">
          <div class="log__meta">
            <span class="log__ver">v{{ rel.version }}</span>
            <span class="log__date ys-num">{{ rel.date }}</span>
          </div>
          <ul v-if="rel.added?.length" class="log__list">
            <li v-for="t in rel.added" :key="'a-' + t">
              <span class="log__tag log__tag--add">新增</span><span>{{ t }}</span>
            </li>
          </ul>
          <ul v-if="rel.fixed?.length" class="log__list">
            <li v-for="t in rel.fixed" :key="'f-' + t">
              <span class="log__tag log__tag--fix">修复</span><span>{{ t }}</span>
            </li>
          </ul>
          <ul v-if="rel.changed?.length" class="log__list">
            <li v-for="t in rel.changed" :key="'c-' + t">
              <span class="log__tag log__tag--opt">优化</span><span>{{ t }}</span>
            </li>
          </ul>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
/**
 * 关于我们(/settings/about)。
 * 纯静态信息页。版本号读 package.json,更新日志读 constants/changelog.js,
 * 两处都不在页面里手写 —— 发版只改数据,不动模板。
 */
import { version as APP_VERSION } from '../../../package.json'
import { ArrowRight, Promotion } from '@element-plus/icons-vue'
import { CHANGELOG } from '@/constants/changelog'
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
