<template>
  <!--
    信誉分卡(2026-10-25 v3 精简版):主界面只给一句话结论,
    规则细节全部收进 ⓘ 气泡——常驻的小字长文案让这张卡像说明书,
    用户第一眼要的只是"多少分、状态好不好、能干什么"。

    数据没拉到/在途时整卡不渲染(同前版理由)。
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
      <li class="tcard__item" data-state="ok">
        <span class="tcard__text">内容产出 +{{ bonus }} 分</span>
        <Tip content="近 30 天内，自己发的每条动态（审核通过后）、每条集市活动、每份过审文件 +1 分，总产出最多加 40 分（其中文件来源最多加 10 分）；被下架或删除的不计" />
      </li>
      <li class="tcard__item" :data-state="badRecord ? 'bad' : 'ok'">
        <span class="tcard__text">{{ badRecord ? '违规记录已扣分' : '无违规记录' }}</span>
        <Tip content="被举报且管理员判定成立，每次 −20 分；被封禁一次 −40 分。多发优质内容可以把分赚回来，30 天后记录不再计" />
      </li>
    </ul>

    <!-- 配额:数字配全称量词,让人不用猜 -->
    <div class="tcard__quota">
      <div class="tcard__quota-row"><span class="tcard__quota-k">同时可建圈子</span><span class="tcard__quota-v">{{ circleQuota }} 个</span></div>
      <div class="tcard__quota-row"><span class="tcard__quota-k">同时在投集市</span><span class="tcard__quota-v">{{ mktQuota }} 条</span></div>
      <div class="tcard__quota-row"><span class="tcard__quota-k">每天可发动态</span><span class="tcard__quota-v">{{ postDaily }} 条</span></div>
      <Tip content="这些上限随信誉分升档：到 80 分时圈子和集市各 2，到 100 分时各 3。集市到期或暂停、圈子解散后，名额立即腾出来" />
    </div>
  </el-card>
</template>

<script setup>
/**
 * 信誉分卡:数据一次拉取,拉到才渲染(加载中/失败都不出壳)。
 * 失败静默——一张缺失的卡只是少个信息,不该在安全设置页弹错误。
 */
import { computed, h, ref } from 'vue'
import { QuestionFilled } from '@element-plus/icons-vue'
import { ElIcon, ElTooltip } from 'element-plus'
import { trustStatus } from '@/api/auth'

/**
 * 气泡:ⓘ 图标 + el-tooltip。用 render 函数而不是字符串 template——
 * vite 的 vue 是 runtime-only 构建(无运行时编译器),字符串 template
 * 会静默渲染为空(2026-10-25 实测踩坑)。
 */
const Tip = {
  props: { content: { type: String, required: true } },
  setup(props) {
    return () => h(ElTooltip, { content: props.content, placement: 'top', showAfter: 150 }, {
      default: () => h('span', {
        class: 'tcard__tip', role: 'note', tabindex: '0', 'aria-label': '说明',
      }, [h(ElIcon, null, { default: () => h(QuestionFilled) })]),
    })
  },
}

const status = ref(null)
const loaded = ref(false)

const trusted = computed(() => status.value?.trusted === true)
const badRecord = computed(() => status.value?.hasBadRecord === true)
const bonus = computed(() => Number(status.value?.creditBonus ?? 0))
const circleQuota = computed(() => Number(status.value?.circleQuota ?? 0))
const mktQuota = computed(() => Number(status.value?.mktQuota ?? 0))
const postDaily = computed(() => Number(status.value?.postDailyQuota ?? 5))
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
  align-items: center;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  line-height: 1.6;
}

.tcard__item[data-state='ok'] .tcard__text {
  color: var(--ys-fg);
}

.tcard__item[data-state='bad'] .tcard__text {
  color: var(--ys-accent-2);
}

/*
 * 配额区:三行键值对(标签写全称,值带量词)。
 * 上一版「建圈 1 · 集市在投 1 · 发帖 30/天」用户看不懂——
 * 省字省到语义消失,还造出「2 个/条」这种混合简写。这里标签用完整短语,
 * 值永远带「个/条」,键值分列对齐。
 */
.tcard__quota {
  position: relative;
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-2) var(--ys-space-3);
  padding-right: 44px;
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  display: grid;
  gap: var(--ys-space-1);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg);
  font-variant-numeric: tabular-nums;
}

/* 块级说明:ⓘ 固定在配额区右上角,不与三行内容抢行。
   span 在 ElTooltip 的 slot 里,不是子组件根节点,必须 :deep() 穿透 */
.tcard__quota :deep(.tcard__tip) {
  position: absolute;
  top: 12px;
  right: 14px;
}

.tcard__quota-row {
  display: flex;
  align-items: baseline;
  gap: var(--ys-space-3);
}

.tcard__quota-k {
  flex: none;
  color: var(--ys-muted);
}

.tcard__quota-v {
  font-weight: var(--ys-weight-medium);
}
</style>

<style scoped>
/* ⓘ 气泡图标:行内小问号,hover/聚焦出气泡(el-tooltip 自带焦点触发) */
.tcard__tip {
  display: inline-flex;
  align-items: center;
  color: var(--ys-muted);
  cursor: help;
  font-size: 14px;
}

.tcard__tip:focus-visible {
  outline: 2px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
}
</style>
