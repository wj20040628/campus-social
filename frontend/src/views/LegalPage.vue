<template>
  <!--
    协议独立页（2026-10-20）。
    与弹窗 LegalAgreementDialog 共用 @/constants/legalDocs 同一份文本。

    为什么需要独立页而不是只有弹窗：
    弹窗没有固定 URL，备案表单要填协议页网址、或想从网站底部挂链接时都没有地址可填。
    这里给《用户协议》/《隐私政策》各一个稳定路由（/agreement、/privacy），
    内容对外可直接访问、无需登录，方便备案与外链。

    页面自带极简顶栏（品牌 + 返回），不依赖 MainLayout（避免 TabBar 与登录态要求）。
  -->
  <div class="legal-page">
    <header class="legal-page__bar">
      <span class="legal-page__brand">友声</span>
      <button type="button" class="legal-page__back" @click="goBack">返回</button>
    </header>

    <main class="legal-page__body">
      <h1 class="legal-page__title">{{ doc_.title }}</h1>
      <p v-for="(p, i) in doc_.paragraphs" :key="i" class="legal-page__p">{{ p }}</p>
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { LEGAL_DOCS } from '@/constants/legalDocs'

const props = defineProps({
  /** 'user' = 用户协议，'privacy' = 隐私政策 */
  doc: { type: String, default: 'user' },
})

const router = useRouter()
const doc_ = computed(() => LEGAL_DOCS[props.doc] || LEGAL_DOCS.user)

// 从站外/书签直接打开时没有上一页可退，退回首页兜底
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<style scoped src="./LegalPage.css"></style>
