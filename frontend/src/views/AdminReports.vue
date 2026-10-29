<template>
  <!--
    举报处置独立页(2026-10-25 从 AdminReview 拆出)。

    【为什么拆】此前举报(有时效)与机审(可等待)挤在同一页四个 tab 里:
    - 页面名"审核处置"、tab 名"内容审核"、默认落点"举报",三个词两个域;
    - 手机 375px 四个 tab 横向滚动;
    - "已处理"(举报)与"机审记录"(机审)两个历史语义打架。
    举报独立成页后,机审页只剩"待审/记录"两个 tab,命名即刻自洽。

    数据、动作、撤销、五态全在 ReportPane 组件内,本页只做编排。
  -->
  <div class="review-admin">
    <div v-if="!ready && !denied" class="review-admin__boot" aria-hidden="true">
      <span class="review-admin__boot-line" />
      <span class="review-admin__boot-line review-admin__boot-line--short" />
    </div>

    <AdminDenied v-else-if="denied" class="review-admin__state" @action="router.replace('/')" />

    <template v-else>
      <header class="review-admin__bar">
        <h1 class="review-admin__title">
          <button type="button" class="review-admin__back" aria-label="返回" @click="router.back()">
            <ArrowLeft class="review-admin__back-icon" aria-hidden="true" />
            <span>举报处置</span>
          </button>
        </h1>
        <p class="review-admin__hint">举报有时效，先处理</p>
      </header>

      <el-tabs v-model="tab" class="review-admin__tabs">
        <el-tab-pane label="待处理" name="report" />
        <el-tab-pane label="已处理" name="processed" />
      </el-tabs>

      <!--
        switch-tab 接到 tab 上（2026-10-28 修）。
        此前两处都写成 `() => {}`：空态里「看看已处理的」按钮点下去毫无反应，
        管理员以为坏了。emit 的值就是目标 tab 名。
      -->
      <ReportPane v-if="tab === 'report'" :handled="false" @switch-tab="(name) => (tab = name)" />
      <ReportPane v-else :handled="true" @switch-tab="(name) => (tab = name)" />
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import ReportPane from '@/components/admin/ReportPane.vue'
import AdminDenied from '@/components/common/AdminDenied.vue'

const router = useRouter()
const userStore = useUserStore()
const tab = ref('report')

const ready = computed(() => !!userStore.userInfo)
const denied = computed(() => ready.value && userStore.isAdmin !== true)

watch(() => userStore.isAdmin, (v) => {
  if (v === false) router.replace('/')
})
</script>

<style scoped src="./AdminReview.css"></style>
