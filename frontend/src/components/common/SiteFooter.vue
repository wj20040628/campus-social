<template>
  <!--
    页脚备案信息（2026-10-28）：备案号是合规硬要求，通常还会被抽查；
    两个号都为空时整块不渲染，不留空壳。
  -->
  <footer v-if="SITE_ICP || SITE_POLICE" class="foot">
    <span class="foot__item">© {{ year }} 友声</span>
    <a
      v-if="SITE_ICP"
      class="foot__item foot__link"
      href="https://beian.miit.gov.cn/"
      target="_blank"
      rel="noopener noreferrer"
    >{{ SITE_ICP }}</a>
    <a
      v-if="SITE_POLICE"
      class="foot__item foot__link"
      href="https://beian.mps.gov.cn/"
      target="_blank"
      rel="noopener noreferrer"
    >{{ SITE_POLICE }}</a>
  </footer>
</template>

<script setup>
import { SITE_ICP, SITE_POLICE } from '@/constants/site'

const year = new Date().getFullYear()
</script>

<style scoped>
.foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 4px var(--ys-space-3);
  padding: var(--ys-space-6) 0 var(--ys-space-3);
  font-size: 12px;
  line-height: 18px;
  color: var(--ys-muted);
}

.foot__link {
  color: inherit;
  text-decoration: none;
}

.foot__link:hover {
  color: var(--ys-fg);
  text-decoration: underline;
}
</style>
