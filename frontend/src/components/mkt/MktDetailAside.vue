<template>
  <!--
    集市活动详情 · 右栏（2026-10-21 从 MktDetail.vue 抽出）。
    抽出原因：MktDetail.vue 加到 415 行，超 400 行门禁。

    三块信息全部由**调用方传入的已有数据**渲染，本组件不发请求：
      这条活动 —— 状态 / 发布 / 剩余 / 留言数
      同样在找 —— 同分类活动条数（父组件用列表接口算好）
      安全提示 —— 静态文案，与留言区警示同一套口径

    仅在桌面端显示（.md__aside 基础态 display:none，媒体查询里打开）。
  -->
  <aside class="md__aside" aria-label="活动信息">
    <section class="panel">
      <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>这条活动</h2>
      <div class="panel__row">
        <span>状态</span>
        <b :class="statusClass">{{ statusText }}</b>
      </div>
      <div class="panel__row"><span>发布</span><b>{{ publishedText }}</b></div>
      <div class="panel__row">
        <span>剩余</span>
        <b :class="{ 'panel__v--warn': timerWarn, 'panel__v--danger': timerExpired }">
          {{ timerText || '已到期' }}
        </b>
      </div>
      <div class="panel__row"><span>留言</span><b>{{ commentCount }} 条</b></div>
    </section>

    <section v-if="sameCatCount !== null" class="panel">
      <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>同样在找</h2>
      <div class="panel__row">
        <span>{{ category }}</span><b>{{ sameCatCount }} 条</b>
      </div>
    </section>

    <section class="panel">
      <h2 class="panel__h"><span class="panel__sq" aria-hidden="true"></span>安全提示</h2>
      <p class="panel__tip">
        <b>只在留言区沟通</b>，不要留手机号、微信号。<br />
        见面选<b>校内公共区域</b>，别单独赴约。<br />
        涉及金钱请当面结清，<b>拒绝转账</b>。
      </p>
    </section>
  </aside>
</template>

<script setup>
defineProps({
  /** 状态文案（投放中 / 已满员 / 已暂停 / 已过期） */
  statusText: { type: String, default: '' },
  /** 状态对应的颜色类：panel__v--live / --warn / --danger */
  statusClass: { type: String, default: '' },
  /** 发布时间（相对时间 + 「发布」） */
  publishedText: { type: String, default: '' },
  /** 倒计时文案 */
  timerText: { type: String, default: '' },
  timerWarn: { type: Boolean, default: false },
  timerExpired: { type: Boolean, default: false },
  /** 留言条数 */
  commentCount: { type: Number, default: 0 },
  /** 活动分类名 */
  category: { type: String, default: '' },
  /** 同分类活动条数；null 表示没算出来（整块隐藏） */
  sameCatCount: { type: Number, default: null },
})
</script>

<style scoped src="./MktDetailAside.css"></style>
