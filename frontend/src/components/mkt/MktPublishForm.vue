<template>
  <!--
    集市活动发布/编辑表单(计划 §发布页)
    ------------------------------------------------------------------
    字段:9 选 1 一级分类、子关键词、标题、描述、投放时长快捷选项 1/3/6/12h
    + 自填、期望人数、价格、地点。底部固定免责声明(计划 §免责声明第 1 处)。
    敏感词命中前端即时提示(计划 §XSS/敏感词)。
    表单体只有一份,壳(MktPublish)负责手机/桌面两种形态。
  -->
  <el-form :model="form" :label-position="labelPosition" class="mpf" @submit.prevent>
    <!-- 一级分类:9 选 1,必选(与后端 CATEGORIES 一致) -->
    <el-form-item label="一级分类" required>
      <el-select v-model="form.category" placeholder="选一个分类" class="mpf__ctl" :teleported="false">
        <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
      </el-select>
      <p v-if="form.categoryError" class="mpf__err" role="alert">{{ form.categoryError }}</p>
    </el-form-item>

    <!-- 游戏:仅「游戏组队」显示,从热门游戏里挑一个,写入 subTag -->
    <el-form-item v-if="form.category === GAME_TEAM" label="想玩的游戏">
      <el-select v-model="form.subTag" clearable placeholder="选一个(可留空)" class="mpf__ctl" :teleported="false">
        <el-option v-for="g in HOT_GAMES" :key="g" :label="g" :value="g" />
      </el-select>
      <p class="mpf__hint">卡片会显示成「游戏组队 · <选中的游戏>」</p>
    </el-form-item>

    <!-- 描述:1–500 -->
    <el-form-item label="详情" required>
      <el-input
        v-model="form.content"
        type="textarea"
        :rows="4"
        class="mpf__ctl"
        maxlength="500"
        show-word-limit
        placeholder="把时间、地点、费用、怎么联系(在平台内)说清楚"
      />
      <p v-if="form.contentError" class="mpf__err" role="alert">{{ form.contentError }}</p>
    </el-form-item>

    <!-- 投放时长:快捷 1/3/6/12h + 自填(编辑模式隐藏:不改 expire_time) -->
    <el-form-item v-if="!isEdit" label="投放时长" required>
      <div class="mpf__durations">
        <button
          v-for="h in DURATIONS"
          :key="h"
          type="button"
          class="mpf__dur"
          :class="{ 'mpf__dur--on': form.launchHours === h }"
          :aria-pressed="form.launchHours === h"
          @click="pickDuration(h)"
        >
          {{ h }}小时
        </button>
        <input
          v-model.number="form.customHours"
          class="mpf__dur-input"
          type="number"
          min="1"
          max="12"
          placeholder="自填"
          :aria-label="`自定义投放时长(1–12 小时)`"
        />
      </div>
      <p class="mpf__hint">单次最长 12 小时,到期自动下架(记录保留,可重新激活)</p>
      <p v-if="form.durError" class="mpf__err" role="alert">{{ form.durError }}</p>
    </el-form-item>

    <!-- 期望人数:纯展示,不做报名计数 -->
    <el-form-item label="期望人数">
      <el-input-number v-model="form.targetNum" :min="0" :max="9999" class="mpf__ctl" />
      <p class="mpf__hint">填 0 就是不限,人数仅供参考</p>
    </el-form-item>

    <!-- 价格:0 免费 -->
    <el-form-item label="价格">
      <el-input-number v-model="form.price" :min="0" :max="999999" :precision="2" :step="1" class="mpf__ctl" />
      <p class="mpf__hint">填 0 = 免费</p>
    </el-form-item>

    <!-- 地点 -->
    <el-form-item label="地点">
      <el-input v-model="form.location" class="mpf__ctl" maxlength="128" placeholder="如 东门小卖部" />
    </el-form-item>

    <!-- 免责声明(计划 §第 1 处:发布页底部固定) -->
    <div class="mpf__disclaimer" role="note">
      温馨提示:本平台仅提供信息展示与线上沟通渠道。所有线下见面、交易,请务必提高安全意识,自行承担风险。请勿私下进行大额转账。
    </div>

    <!-- 总错误:贴近提交按钮,不弹 toast(抽屉会挡住) -->
    <p v-if="form.error" class="mpf__err mpf__err--top" role="alert">{{ form.error }}</p>
  </el-form>
</template>

<script setup>
/**
 * MktPublishForm.vue —— 集市活动发布/编辑表单体
 *
 * 一级分类白名单与后端 MktActivityServiceImpl.CATEGORIES 保持一致(9 选 1),
 * 自填时长上限 12h 与后端 MAX_LAUNCH_HOURS 一致。编辑模式(isEdit)隐藏时长选择,
 * 因为编辑只改文案、不改 expire_time(计划 §编辑语义)。
 */
import { DURATION_OPTIONS, CATEGORIES, HOT_GAMES } from './mktConstants'
import { watch } from 'vue'

const props = defineProps({
  form: { type: Object, required: true },
  /** 编辑模式:隐藏投放时长(不改 expire_time),错误文案由 form.error 承载 */
  isEdit: { type: Boolean, default: false },
  labelPosition: { type: String, default: 'top' },
})

const DURATIONS = DURATION_OPTIONS
const GAME_TEAM = '游戏组队'

function pickDuration(hours) {
  props.form.launchHours = hours
  props.form.customHours = null
}

// 切走「游戏组队」时清掉已选游戏,避免残留 subTag
watch(
  () => props.form.category,
  (cat, prev) => {
    if (cat !== GAME_TEAM && prev === GAME_TEAM) {
      props.form.subTag = ''
    }
  },
)
</script>

<style scoped>
.mpf {
  display: flex;
  flex-direction: column;
}

.mpf__ctl {
  width: 100%;
}

.mpf :deep(.el-form-item__label) {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

/* 时长快捷选项:一行四个胶囊 + 自填输入 */
.mpf__durations {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--ys-space-2);
}

.mpf__dur {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg-2);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out),
    color var(--ys-motion-fast) var(--ys-ease-out);
}

.mpf__dur:hover { background: var(--ys-surface-2); }

.mpf__dur--on {
  border-color: var(--ys-accent);
  background: var(--ys-accent-soft);
  color: var(--ys-accent);
  font-weight: var(--ys-weight-medium);
}

.mpf__dur-input {
  min-height: var(--ys-touch-min);
  width: 88px;
  padding: 0 var(--ys-space-3);
  border: 1px solid var(--ys-border-control);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  color: var(--ys-fg);
}

.mpf__dur-input:focus-visible {
  border-color: var(--ys-accent);
  box-shadow: var(--ys-focus-ring);
  outline: none;
}

.mpf__hint {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.mpf__err {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

.mpf__err--top {
  margin-top: var(--ys-space-3);
}

/* 免责声明(第 1 处):吸在表单底部,视觉上固定 */
.mpf__disclaimer {
  margin-top: var(--ys-space-4);
  padding: var(--ys-space-3) var(--ys-space-4);
  border: 1px solid var(--ys-border-soft);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
  color: var(--ys-muted);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-sm);
}
</style>
