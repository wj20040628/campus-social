<template>
  <!--
    信誉分卡(2026-10-25 v2,统一概念:原「发布信誉」退役)。

    设计三原则(与后端 UserTrustStatusVO 注释同源):
    1. 一张卡讲清三件事:我现在多少分 / 分数怎么来的 / 分数换来什么配额;
    2. 给路径不给惩罚感:扣了分写「怎么加回来」,不渲染成信用黑名单;
    3. 与闸门同源:展示的分数/配额与后端实际判定严格一致。

    数据没拉到/在途时整卡不渲染:加载中的空壳会闪一版「60 分」,
    那是对用户的错误告知,比晚一秒出现更糟。
  -->
  <el-card v-if="loaded" class="tcard">
    <div class="tcard__head">
      <h3 class="tcard__title">信誉分</h3>
      <span v-if="score !== null" class="tcard__score" :data-good="score >= 60">
        {{ score }}<span class="tcard__score-max">/100</span>
      </span>
      <span class="tcard__badge" :data-trusted="trusted ? 'good' : 'building'">
        {{ trusted ? '良好' : '提升中' }}
      </span>
    </div>

    <ul class="tcard__list">
      <li class="tcard__item" :data-state="bonus > 0 ? 'ok' : 'plain'">
        <el-icon class="tcard__mark" aria-hidden="true"><CircleCheckFilled v-if="bonus > 0" /><Clock v-else /></el-icon>
        <span class="tcard__text">
          内容产出 +{{ bonus }} 分
          <span class="tcard__hint">近 {{ decayDays }} 天每条过审动态/集市活动 +1,封顶 +40;被下架的不计</span>
        </span>
      </li>
      <li v-if="badRecord" class="tcard__item" data-state="bad">
        <el-icon class="tcard__mark" aria-hidden="true"><CircleCloseFilled /></el-icon>
        <span class="tcard__text">
          违规记录已扣分
          <span class="tcard__hint">成立举报 −20/条、封禁 −40/次;持续产出可补回,记录满 {{ decayDays }} 天不再计</span>
        </span>
      </li>
      <li v-else class="tcard__item" data-state="ok">
        <el-icon class="tcard__mark" aria-hidden="true"><CircleCheckFilled /></el-icon>
        <span class="tcard__text">近 {{ decayDays }} 天无成立举报与封禁记录</span>
      </li>
      <li v-if="!verified" class="tcard__item" data-state="todo">
        <el-icon class="tcard__mark" aria-hidden="true"><CircleCloseFilled /></el-icon>
        <span class="tcard__text">
          未完成学号认证
          <span class="tcard__hint">认证后解锁建圈与集市发布,发帖额度 3 → 30 条/天</span>
        </span>
      </li>
    </ul>

    <!-- 配额:分数的兑现。未认证显示锁定态,认证后显示三档 -->
    <div class="tcard__quota">
      <div class="tcard__quota-row">
        <span class="tcard__quota-k">建圈</span>
        <span v-if="verified" class="tcard__quota-v">{{ circleQuota }} 个<template v-if="circleQuota < 3"> · 信誉 {{ nextTierAt }} 分升到 {{ circleQuota + 1 }} 个</template></span>
        <span v-else class="tcard__quota-v tcard__quota-v--lock">认证后解锁</span>
      </div>
      <div class="tcard__quota-row">
        <span class="tcard__quota-k">集市在投</span>
        <span v-if="verified" class="tcard__quota-v">{{ mktQuota }} 条<template v-if="mktQuota < 3"> · 信誉 {{ nextTierAt }} 分升到 {{ mktQuota + 1 }} 条</template></span>
        <span v-else class="tcard__quota-v tcard__quota-v--lock">认证后解锁</span>
      </div>
      <div class="tcard__quota-row">
        <span class="tcard__quota-k">发帖</span>
        <span class="tcard__quota-v">{{ postDaily }} 条/天<template v-if="!verified"> · 认证后 30 条</template></span>
      </div>
    </div>

    <p class="tcard__foot">
      {{ trusted
        ? '信誉良好：命中敏感词的内容可直接发布（展示时自动打码），配额随分数上升扩容'
        : '分数回到 60 以上恢复「直发」待遇；发优质内容是最快的加分方式' }}
    </p>
  </el-card>
</template>

<script setup>
/**
 * 信誉分卡:数据一次拉取,拉到才渲染(加载中/失败都不出壳)。
 * 失败静默——一张缺失的卡只是少个信息,不该在安全设置页弹错误。
 */
import { computed, ref } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, Clock } from '@element-plus/icons-vue'
import { trustStatus } from '@/api/auth'

const status = ref(null)
const loaded = ref(false)

const trusted = computed(() => status.value?.trusted === true)
const verified = computed(() => status.value?.verified === true)
const badRecord = computed(() => status.value?.hasBadRecord === true)
/** 产出加分(后端已按封顶截断;接口旧版本无此字段按 0 兜底,只影响展示) */
const bonus = computed(() => Number(status.value?.creditBonus ?? 0))
const decayDays = computed(() => Number(status.value?.creditDecayDays ?? 30))
const circleQuota = computed(() => Number(status.value?.circleQuota ?? 0))
const mktQuota = computed(() => Number(status.value?.mktQuota ?? 0))
const postDaily = computed(() => Number(status.value?.postDailyQuota ?? 3))
/** 下一档分数线(75/90),已达顶档时不会渲染 */
const nextTierAt = computed(() => (circleQuota.value <= 1 ? 75 : 90))
const score = computed(() => {
  const s = status.value?.creditScore
  return s == null ? null : Number(s)
})

trustStatus()
  .then((d) => { status.value = d; loaded.value = true })
  .catch(() => { loaded.value = false })
</script>

<style scoped>
.tcard {
  margin-bottom: var(--ys-space-4);
}

.tcard__head {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.tcard__title {
  margin: 0;
  font-size: var(--ys-text-base);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

/* 分数是这张卡的主角:大号 tabular,≥60 主色 / <60 警示色 */
.tcard__score {
  margin-left: auto;
  font-size: var(--ys-text-2xl);
  font-weight: var(--ys-weight-semibold);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  color: var(--ys-accent);
}

.tcard__score[data-good='false'] {
  color: var(--ys-warning);
}

.tcard__score-max {
  margin-left: 2px;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

.tcard__badge {
  padding: 0 var(--ys-space-2);
  border-radius: 999px;
  font-size: var(--ys-text-xs);
  line-height: 20px;
}

.tcard__badge[data-trusted='good'] {
  background: var(--ys-accent);
  color: var(--ys-accent-on);
}

/* 提升中用中性色:它是「还没到」不是「做错了」 */
.tcard__badge[data-trusted='building'] {
  background: var(--ys-surface-2);
  color: var(--ys-muted);
}

.tcard__list {
  list-style: none;
  margin: var(--ys-space-3) 0 0;
  padding: 0;
  display: grid;
  gap: var(--ys-space-2);
}

.tcard__item {
  display: flex;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  line-height: 1.6;
}

.tcard__mark {
  flex: none;
  font-size: 16px;
  margin-top: 2px;
}

.tcard__item[data-state='ok'] .tcard__mark {
  color: var(--ys-accent);
}

.tcard__item[data-state='todo'] .tcard__mark,
.tcard__item[data-state='bad'] .tcard__mark {
  color: var(--ys-accent-2);
}

.tcard__item[data-state='wait'] .tcard__mark,
.tcard__item[data-state='plain'] .tcard__mark {
  color: var(--ys-muted);
}

.tcard__item[data-state='ok'] .tcard__text {
  color: var(--ys-fg);
}

.tcard__item[data-state='todo'] .tcard__text,
.tcard__item[data-state='bad'] .tcard__text,
.tcard__item[data-state='wait'] .tcard__text,
.tcard__item[data-state='plain'] .tcard__text {
  color: var(--ys-muted);
}

.tcard__hint {
  display: block;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}

/* 配额区:分数换来的实际待遇,一张小表 */
.tcard__quota {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  display: grid;
  gap: var(--ys-space-1);
}

.tcard__quota-row {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
  font-size: var(--ys-text-sm);
}

.tcard__quota-k {
  flex: none;
  width: 4.5em;
  color: var(--ys-muted);
}

.tcard__quota-v {
  color: var(--ys-fg);
  font-variant-numeric: tabular-nums;
}

.tcard__quota-v--lock {
  color: var(--ys-muted);
}

.tcard__foot {
  margin: var(--ys-space-3) 0 0;
  padding-top: var(--ys-space-3);
  border-top: 1px solid var(--ys-border);
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
}
</style>
