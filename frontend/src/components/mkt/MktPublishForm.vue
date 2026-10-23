<template>
  <!--
    集市活动发布/编辑表单(计划 §发布页)
    ------------------------------------------------------------------
    字段:9 选 1 一级分类、子关键词、详情、投放时长快捷选项 1/3/6/12h
    + 自填、期望人数、价格、地点。底部固定免责声明(计划 §免责声明第 1 处)。
    敏感词命中前端即时提示(计划 §XSS/敏感词)。
    表单体只有一份,壳(MktPublish)负责手机/桌面两种形态。

    【2026-10-21 重做视觉结构】
    改版前一版的做法是「每个字段一张带边框白卡」，四项叠在一起变成四个盒子，
    加上为省高度而压到 10px 的内边距与 11px 标签，整体非常挤、像半成品。
    现在改成**一张卡 + 内部分隔线**，并把「标签在左、控件在右」用于单值字段：
      行式：一级分类 / 期望人数 / 价格 / 地点（标签固定宽，控件靠右）
      块式：详情 / 投放时长（需要整行的内容）
    这样既保住了「一屏填完」，又不用靠压内边距硬挤 —— 空间是靠**结构**省的。

    同时不再用 el-form / el-form-item：本表单的校验是手写 validate()，
    没有用到 el-form 的 rules，套一层只为拿 label 布局反而要写一堆 :deep 覆盖。
    原生 form + 自己的行结构更可控，DOM 也干净。
  -->
  <form class="mpf" @submit.prevent>
    <div class="mpf__card">
      <!-- 一级分类:9 选 1,必选(与后端 CATEGORIES 一致) -->
      <div class="mpf__row">
        <label class="mpf__k" for="mpf-cat">一级分类<span class="mpf__req">*</span></label>
        <el-select
          id="mpf-cat"
          v-model="form.category"
          placeholder="选一个分类"
          class="mpf__v"
          :teleported="false"
        >
          <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
        </el-select>
      </div>
      <p v-if="form.categoryError" class="mpf__err" role="alert">{{ form.categoryError }}</p>

      <!-- 游戏:仅「游戏组队」显示,从热门游戏里挑一个,写入 subTag -->
      <div v-if="form.category === GAME_TEAM" class="mpf__row">
        <label class="mpf__k" for="mpf-game">想玩什么</label>
        <el-select
          id="mpf-game"
          v-model="form.subTag"
          clearable
          placeholder="选一个(可留空)"
          class="mpf__v"
          :teleported="false"
        >
          <el-option v-for="g in HOT_GAMES" :key="g" :label="g" :value="g" />
        </el-select>
      </div>

      <!--
        标题（2026-10-21 补）。
        后端 MktActivityDTO 一直是支持 title 的（optionalTitle 校验，上限 25 字），
        但前端发布页从来没给这个输入框 —— 于是 title 恒为空，
        后端走 derivedTitle(category, subTag) 兜底，把「分类 · 游戏」当标题存进库。
        结果就是卡片上的标题和分类徽章一字不差（列表卡片只能靠 showTitle 判断不渲染）。

        现在补上输入框：用户能写一句具体的（「今晚开黑缺一个打野」），
        卡片才真正有主文案。留空时仍走后端兜底，不影响旧数据。
      -->
      <div class="mpf__row">
        <label class="mpf__k" for="mpf-title">标题</label>
        <el-input
          id="mpf-title"
          v-model="form.title"
          class="mpf__v"
          maxlength="25"
          placeholder="一句话说清"
        />
      </div>

      <!-- 详情:1–500。块式 —— 多行输入需要整行宽度 -->
      <div class="mpf__block">
        <label class="mpf__k" for="mpf-content">详情<span class="mpf__req">*</span></label>
        <el-input
          id="mpf-content"
          v-model="form.content"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="把时间、地点、费用、怎么联系(在平台内)说清楚"
        />
      </div>
      <p v-if="form.contentError" class="mpf__err" role="alert">{{ form.contentError }}</p>

      <!--
        投放时长（2026-10-21 改成一行）。
        改版前是「4 个按钮一行 + 自填输入框另占一行」，共两行约 92px。
        现在 4 个快捷按钮 + 自填输入框挤在同一行：
          按钮各占 1fr，自填框固定 92px，加起来正好填满 358px 容器宽。
        省掉一整行高度，且不用靠压内边距 —— 是「结构省空间」。
      -->
      <div v-if="!isEdit" class="mpf__block">
        <div class="mpf__krow">
          <label class="mpf__k" for="mpf-dur">投放时长<span class="mpf__req">*</span></label>
          <span class="mpf__knote">最长 12 小时</span>
        </div>
        <div class="mpf__durs">
          <button
            v-for="h in DURATIONS"
            :key="h"
            type="button"
            class="mpf__dur"
            :class="{ 'mpf__dur--on': form.launchHours === h }"
            :aria-pressed="form.launchHours === h"
            @click="pickDuration(h)"
          >
            {{ h }}h
          </button>
          <!--
            自填：第五个格子（2026-10-21 加回上下箭头）。
            用 el-input-number + controls-position="right"：
            右侧一列上下箭头，点一下 ±1 小时。

            :min 设为 0 而不是 1 —— el-input-number 在初始值 null 时，
            点一次减号会先被钳到 :min 再减，如果 min=1 就永远停在 1，
            没法从「未填」递减到合法区间。设 0 让组件有个可退的起点，
            真正的下限 1 由 onHoursInput 夹（见 script 里的说明）。
          -->
          <el-input-number
            id="mpf-dur"
            v-model="form.customHours"
            :min="0"
            :max="12"
            :step="1"
            :precision="0"
            size="small"
            controls-position="right"
            class="mpf__custom"
            placeholder="自填"
            aria-label="自定义投放时长（1–12 小时）"
            @change="onHoursInput"
          />
        </div>
      </div>
      <p v-if="form.durError" class="mpf__err" role="alert">{{ form.durError }}</p>

      <!--
        期望人数 / 价格：行式 + 自绘步进器。
        输入框用 type="text" + inputmode="numeric" 而不是 type="number" ——
        已经有自绘的 −/+ 按钮，不需要浏览器再叠一对原生小箭头；
        而原生箭头的隐藏依赖 ::-webkit-inner-spin-button，
        在 Firefox 下要另写 -moz-appearance，改用 text 就从根源上没有它。
        inputmode 保证手机上仍然弹出数字键盘。
      -->
      <div class="mpf__row">
        <label class="mpf__k" for="mpf-num">期望人数</label>
        <div class="mpf__v mpf__stepper">
          <button
            type="button"
            class="mpf__step"
            aria-label="期望人数减一"
            @click="stepNum('targetNum', -1)"
          >−</button>
          <el-input
            id="mpf-num"
            :model-value="form.targetNum"
            type="text"
            inputmode="numeric"
            class="mpf__numv"
            aria-label="期望人数"
            @update:model-value="onCountInput"
          />
          <button
            type="button"
            class="mpf__step"
            aria-label="期望人数加一"
            @click="stepNum('targetNum', 1)"
          >+</button>
        </div>
      </div>

      <div class="mpf__row">
        <label class="mpf__k" for="mpf-price">价格</label>
        <div class="mpf__v mpf__stepper">
          <button
            type="button"
            class="mpf__step"
            aria-label="价格减一"
            @click="stepNum('price', -1)"
          >−</button>
          <el-input
            id="mpf-price"
            :model-value="form.price"
            type="text"
            inputmode="decimal"
            class="mpf__numv"
            aria-label="价格"
            @update:model-value="onPriceInput"
          />
          <button
            type="button"
            class="mpf__step"
            aria-label="价格加一"
            @click="stepNum('price', 1)"
          >+</button>
        </div>
      </div>

      <!-- 地点 -->
      <div class="mpf__row">
        <label class="mpf__k" for="mpf-loc">地点</label>
        <el-input
          id="mpf-loc"
          v-model="form.location"
          class="mpf__v"
          maxlength="128"
          placeholder="如 东门小卖部"
        />
      </div>

      <!-- 三个选填项的说明并到一行，省掉三行小字 -->
      <p class="mpf__note">期望人数填 0 = 不限 · 价格填 0 = 免费 · 地点可不填</p>
    </div>

    <!-- 免责声明(计划 §第 1 处:发布页底部固定) -->
    <div class="mpf__disclaimer" role="note">
      温馨提示:本平台仅提供信息展示与线上沟通渠道。所有线下见面、交易,请务必提高安全意识,自行承担风险。请勿私下进行大额转账。
    </div>

    <!-- 总错误:贴近提交按钮,不弹 toast -->
    <p v-if="form.error" class="mpf__err mpf__err--top" role="alert">{{ form.error }}</p>
  </form>
</template>

<script setup>
/**
 * MktPublishForm.vue —— 集市活动发布/编辑表单体
 *
 * 一级分类白名单与后端 MktActivityServiceImpl.CATEGORIES 保持一致(9 选 1),
 * 自填时长上限 12h 与后端 MAX_LAUNCH_HOURS 一致。编辑模式(isEdit)隐藏时长选择,
 * 因为编辑只改文案、不改 expire_time(计划 §编辑语义)。
 *
 * 【2026-10-21 重做】视觉结构与紧凑策略都换了：
 *   一张卡 + 分隔线（原来是四个带边框的盒子，堆在一起很挤）
 *   单值字段改行式布局（标签左、控件右），靠结构省高度而不是压内边距
 *   数字输入自带 -/+ 按钮，替掉 el-input-number 被压窄的小箭头
 */
import { DURATION_OPTIONS, CATEGORIES, HOT_GAMES } from './mktConstants'
import { watch } from 'vue'

const props = defineProps({
  form: { type: Object, required: true },
  /** 编辑模式:隐藏投放时长(不改 expire_time),错误文案由 form.error 承载 */
  isEdit: { type: Boolean, default: false },
})

const DURATIONS = DURATION_OPTIONS
const GAME_TEAM = '游戏组队'

function pickDuration(hours) {
  props.form.launchHours = hours
  props.form.customHours = null
}

/**
 * 步进器（2026-10-21 加）。
 *
 * 只对「期望人数」和「价格」用。它是自绘的 -/+ 按钮，
 * 热区 36px × 36px，比 el-input-number 在两列窄格里被压成的小箭头好点得多。
 * 下限 0；价格允许小数（精度交给后端与显示格式，这里不做四舍五入，避免吞输入）。
 */
function stepNum(key, delta) {
  const cur = Number(props.form[key]) || 0
  const next = cur + delta
  props.form[key] = next < 0 ? 0 : next
}

/* ---------------- 文本输入的数字过滤（2026-10-21） ---------------- */
/*
 * 输入框从 type="number" 改成 type="text" 后，浏览器不再帮我们拦住非数字字符，
 * 所以这三个处理函数各做一次过滤 —— 否则用户能输入「abc」，提交时才报错。
 * 保留空串：用户全选删除时应当是能清空的，不能强行写回 0。
 */
function onCountInput(v) {
  const digits = String(v ?? '').replace(/[^\d]/g, '')
  props.form.targetNum = digits === '' ? '' : Number(digits)
}

function onPriceInput(v) {
  // 价格允许一个小数点，其余非数字字符一律剔除
  let s = String(v ?? '').replace(/[^\d.]/g, '')
  const i = s.indexOf('.')
  if (i >= 0) s = s.slice(0, i + 1) + s.slice(i + 1).replace(/\./g, '')
  props.form.price = s === '' ? '' : s
}

/**
 * 自填小时数（2026-10-21 改用 el-input-number）。
 *
 * 改版前这里接的是 type="text" 的字符串，所以要自己剥非数字字符。
 * 现在是 el-input-number：值已经是 number | null，:min/:max 也由它兜住，
 * 这里只需再夹一次 —— 用户手敲超过 12 时，组件依赖失焦才夹，
 * 我们提前夹掉能让「填 15 报错」变成「自动变 12」，少一次无谓的错误提示。
 */
function onHoursInput(v) {
  /*
   * 0 与空都视为「没填自填」，交给 effectiveHours 回落到快捷选项。
   * 组件层 :min 设的是 0（不是 1），原因见模板里的注释：
   * el-input-number 在初始 null 时点减号会先钳到 :min 再减，
   * 若 min=1 就永远停在 1，用户没法从空值进入合法区间。
   * 真正的下限 1 在这里夹。
   */
  if (v == null || v === '' || Number(v) <= 0) {
    props.form.customHours = null
    return
  }
  const n = Number(v)
  if (!Number.isFinite(n)) {
    props.form.customHours = null
    return
  }
  props.form.customHours = Math.min(12, Math.max(1, Math.round(n)))
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

<style scoped src="./MktPublishForm.css"></style>
