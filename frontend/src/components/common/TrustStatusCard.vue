<template>
  <!--
    发布信誉卡(2026-10-24,设置页「账号与安全」)。

    设计三原则(与后端 UserTrustStatusVO 的注释同源):
    1. 给清单不给公式:三条清单说清「怎么变好」,不披露变体检测等审核细节;
    2. 给路径不给惩罚感:「建设中」而非「低信誉」,未达标项都带一句怎么完成;
    3. 与闸门同源:清单必须和 ReviewTrustService 实际判定一致,
       显示达标却仍被送审会直接摧毁这张卡的可信度。

    数据没拉到/在途时整卡不渲染:加载中的空壳会闪一版「建设中」,
    那是对用户的错误告知,比晚一秒出现更糟。
  -->
  <el-card v-if="loaded" class="tcard">
    <div class="tcard__head">
      <h3 class="tcard__title">发布信誉</h3>
      <!-- 信誉分(2026-10-25):100−成立举报×20−封禁×40,180 天窗口,受信≥60 -->
      <span v-if="score !== null" class="tcard__score" :data-good="score >= 60">
        信誉 {{ score }} 分
      </span>
      <span class="tcard__badge" :data-trusted="trusted ? 'good' : 'building'">
        {{ trusted ? '良好' : '建设中' }}
      </span>
    </div>

    <ul class="tcard__list">
      <li class="tcard__item" :data-state="verified ? 'ok' : 'todo'">
        <el-icon class="tcard__mark" aria-hidden="true">
          <CircleCheckFilled v-if="verified" />
          <CircleCloseFilled v-else />
        </el-icon>
        <span class="tcard__text">
          {{ verified ? '已完成学号认证' : '未完成学号认证' }}
          <span v-if="!verified" class="tcard__hint">在「完善资料」中提交后,认证通过即点亮</span>
        </span>
      </li>
      <li class="tcard__item" :data-state="ageOk ? 'ok' : 'wait'">
        <el-icon class="tcard__mark" aria-hidden="true">
          <CircleCheckFilled v-if="ageOk" />
          <Clock v-else />
        </el-icon>
        <span class="tcard__text">
          <template v-if="ageOk">账号注册满 {{ minDays }} 天</template>
          <template v-else>账号注册第 {{ ageDays }} 天,满 {{ minDays }} 天自动满足</template>
        </span>
      </li>
      <li class="tcard__item" :data-state="badRecord ? 'bad' : 'ok'">
        <el-icon class="tcard__mark" aria-hidden="true">
          <CircleCheckFilled v-if="!badRecord" />
          <CircleCloseFilled v-else />
        </el-icon>
        <span class="tcard__text">
          {{ badRecord ? '近 180 天有成立举报或封禁记录' : '近 180 天无成立举报与封禁记录' }}
          <span v-if="badRecord" class="tcard__hint">记录满 180 天后自动不再计分</span>
        </span>
      </li>
    </ul>

    <p class="tcard__foot">
      {{ trusted
        ? '命中敏感词的帖子可直接发布（展示时自动打码）'
        : '完成以上各项后，发帖审核更快捷' }}
    </p>
  </el-card>
</template>

<script setup>
/**
 * 信誉卡:数据一次拉取,拉到才渲染(加载中/失败都不出壳)。
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
const ageDays = computed(() => Number(status.value?.accountAgeDays ?? 0))
const minDays = computed(() => Number(status.value?.trustMinDays ?? 14))
const ageOk = computed(() => ageDays.value >= minDays.value)
/** 信誉分;接口旧版本无此字段时为 null,分数行不渲染 */
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

/* 建设中用中性色:它是「还没到」不是「做错了」 */
.tcard__badge[data-trusted='building'] {
  background: var(--ys-surface-2);
  color: var(--ys-muted);
}

/* 信誉分:≥60 用主色,低于 60 用警示色(它正在挡住「直发」待遇) */
.tcard__score {
  margin-left: auto;
  font-size: var(--ys-text-xs);
  font-weight: var(--ys-weight-semibold);
  font-variant-numeric: tabular-nums;
  color: var(--ys-accent);
}

.tcard__score[data-good='false'] {
  color: var(--ys-warning);
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

.tcard__item[data-state='wait'] .tcard__mark {
  color: var(--ys-muted);
}

.tcard__item[data-state='ok'] .tcard__text {
  color: var(--ys-fg);
}

.tcard__item[data-state='todo'] .tcard__text,
.tcard__item[data-state='bad'] .tcard__text,
.tcard__item[data-state='wait'] .tcard__text {
  color: var(--ys-muted);
}

.tcard__hint {
  display: block;
  font-size: var(--ys-text-xs);
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
