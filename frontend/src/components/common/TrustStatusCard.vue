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
        <Tip content="近 30 天每条过审动态 / 集市活动 +1 分，封顶 +40；被下架或删除的不计" />
      </li>
      <li class="tcard__item" :data-state="badRecord ? 'bad' : 'ok'">
        <span class="tcard__text">{{ badRecord ? '违规记录已扣分' : '无违规记录' }}</span>
        <Tip content="成立举报 −20/条，封禁 −40/次；持续产出可补回，记录满 30 天不再计" />
      </li>
      <li v-if="!verified" class="tcard__item" data-state="bad">
        <span class="tcard__text">未完成学号认证</span>
        <Tip content="认证后解锁建圈与集市发布，发帖额度 3 → 30 条/天" />
      </li>
    </ul>

    <!-- 配额一行式:分数换来的实际待遇 -->
    <div class="tcard__quota">
      <span class="tcard__quota-item">建圈 {{ circleQuota }}</span>
      <span class="tcard__quota-sep">·</span>
      <span class="tcard__quota-item">集市在投 {{ mktQuota }}</span>
      <span class="tcard__quota-sep">·</span>
      <span class="tcard__quota-item">发帖 {{ postDaily }}/天</span>
      <Tip content="配额随分数升档：75 分 → 2 个/条，90 分 → 3 个/条；集市过期或暂停即释放名额，圈子解散释放名额" />
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
const verified = computed(() => status.value?.verified === true)
const badRecord = computed(() => status.value?.hasBadRecord === true)
const bonus = computed(() => Number(status.value?.creditBonus ?? 0))
const circleQuota = computed(() => Number(status.value?.circleQuota ?? 0))
const mktQuota = computed(() => Number(status.value?.mktQuota ?? 0))
const postDaily = computed(() => Number(status.value?.postDailyQuota ?? 3))
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

/* 配额一行式:浅底横条,数字 tabular */
.tcard__quota {
  margin-top: var(--ys-space-3);
  padding: var(--ys-space-2) var(--ys-space-3);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg);
  font-variant-numeric: tabular-nums;
}

.tcard__quota-item {
  font-weight: var(--ys-weight-medium);
}

.tcard__quota-sep {
  color: var(--ys-muted);
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
