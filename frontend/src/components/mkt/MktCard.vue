<template>
  <!--
    集市活动卡片(广场 / 我的发布 同款,形态差异收在内部)
    ------------------------------------------------------------------
    广场(variant=plaza):纯展示,整卡可点 → 详情。
    我的发布(variant=my):左侧一列状态灯 + 右侧操作按钮组;
      重激活仅在 暂停/过期 时可见(计划 §卡片要素)。
    倒计时 1s 间隔,剩余 ≤ 2h 切橙红。

    【2026-10-21 归属用颜色区分】自己发的活动加 .mktcard--mine 类：
    描边换主色、底色带一点主色倾向。不再用「我的」文字徽标 ——
    徽标在手机两列的窄卡里要换行、会挤掉分类名，成本高于收益。
  -->
  <div
    class="mktcard"
    :class="[variant === 'my' ? 'mktcard--my' : 'mktcard--plaza', { 'mktcard--mine': isMine }]"
  >
    <!-- 我的发布:状态灯(图标 + 文字双通道,不只靠颜色) -->
    <div v-if="variant === 'my'" class="mktcard__lamp">
      <span class="mktcard__lamp-dot" :class="`mktcard__lamp-dot--${status}`" aria-hidden="true" />
      <span class="mktcard__lamp-text">{{ statusText }}</span>
    </div>

    <!-- 可点主体:广场整卡,我的发布只点标题区 -->
    <button
      type="button"
      class="mktcard__open"
      @click="$emit('open', activity.id)"
    >
      <!--
        顶行：分类 + 副标签 + 满员标记。
        【2026-10-21 改】「我的」徽标去掉，改用卡片配色区分归属：
        自己发的卡片描边用主色、底色带一点点主色倾向（见 .mktcard--mine）。
        理由是徽标要在 173px 的窄卡里换行、挤掉分类名，成本高于收益；
        而一张卡有没有「属于我」的底色，扫一眼就能感到，不需要读文字。
      -->
      <div class="mktcard__top">
        <span class="mktcard__cat">{{ activity.category }}</span>
        <span v-if="activity.subTag" class="mktcard__sub">{{ activity.subTag }}</span>
        <span v-if="isFull" class="mktcard__full">已满</span>
      </div>

      <!--
        主文案 + 内容预览（2026-10-21 修标题重复）。
        后端 derivedTitle() 在用户没填标题时会拿「分类」兜底（mainText === category），
        于是分类徽章和标题会是同一个词。现在两者相同时不渲染标题行。
      -->
      <p v-if="showTitle" class="mktcard__title">{{ titleText }}</p>
      <p class="mktcard__preview">{{ activity.content }}</p>

      <!--
        信息行（2026-10-21 重排）：地点 / 价格 / 目标人数。
        改版前「目标 N 人」在底部与时间挤一行，两列窄卡里放不下（实测溢出）。
        它和地点、价格同属「这条活动的基本参数」，合并到一行更自然。
      -->
      <div class="mktcard__info">
        <span v-if="activity.location" class="mktcard__meta">
          <el-icon :size="13"><Location /></el-icon>{{ activity.location }}
        </span>
        <span class="mktcard__meta">
          <el-icon :size="13"><Coin /></el-icon>{{ priceText }}
        </span>
        <span v-if="activity.targetNum" class="mktcard__meta">
          <el-icon :size="13"><User /></el-icon>目标 {{ activity.targetNum }} 人
        </span>
      </div>

      <!--
        底部行（2026-10-21 重排）：只剩「剩余时间」。
        改成带前缀的写法「剩余时间：剩 2时 46分」—— 原来只有一个时钟图标 +
        倒计时文本，读者要自己判断这串数字是「剩余」还是「已经持续」。
        目标人数已移到上面的信息行。
        集市是限时活动，这一行是决定点不点的关键，所以单独成行、紧迫时变色。
        不做 x/N 报名进度：后端只有 target_num，没有已报名人数，编数字比不显示更糟。

        【v-if 放在容器上而不是内部的 span】
        改版前 v-if 只挂在里面的 span 上，于是「我的发布」里（variant==='my'，
        倒计时不显示）整个 .mktcard__foot 仍然被渲染 —— 一个只有
        padding-top:8px + border-top 的空容器，就是用户看到的那条空行。
        现在整块按条件渲染，没有内容就不产生任何盒子。
      -->
      <div v-if="variant === 'plaza' && timerText" class="mktcard__foot">
        <span
          class="mktcard__timer"
          :class="{ 'mktcard__timer--warn': timerWarn }"
        >
          <el-icon :size="13"><Timer /></el-icon>剩余时间：{{ timerText }}
        </span>
      </div>
    </button>

    <!--
      我的发布：操作按钮组（2026-10-21 按四态矩阵重写）。

      【为什么重写】改版前按钮是固定渲染的，只在少数几个上挂 v-if，
      于是出现过「已过期的活动还挂着标满员 / 取消满员」——
      过期活动既不在广场也收不到人，标满员等于给尸体盖章。
      同类错位还有「已暂停时还挂着标满员」：藏起来的东西不需要再声明人找齐了。

      现在每个状态下只渲染**真正会改变结果**的按钮：
        LAUNCHING 标满员 · 暂停 | 编辑 · 删除
        FULL      取消满员 · 暂停 | 编辑 · 删除
        PAUSED    开启投放 | 编辑 · 删除
        EXPIRED   重新激活 | 编辑 · 删除
      破坏性动作（删除）固定最右并标红。
    -->
    <div v-if="variant === 'my'" class="mktcard__ops">
      <!--
        注意：这里**没有**「置顶」。
        集市没有置顶功能 —— mkt_activity 表无 is_top 列，后端也没有对应接口。
        （公告有置顶、集市没有，两者不是同一套模型，不能照搬。）
      -->

      <!-- 投放中 / 已满员 都能切换满员状态（两者都有意义） -->
      <button
        v-if="status === 'LAUNCHING' || status === 'FULL'"
        type="button"
        class="mktcard__op"
        @click="$emit('full', activity)"
      >
        <el-icon :size="15"><Medal /></el-icon>
        <span>{{ status === 'FULL' ? '取消满员' : '标满员' }}</span>
      </button>

      <!-- 打开投放：暂停态 -->
      <button
        v-if="status === 'PAUSED'"
        type="button"
        class="mktcard__op mktcard__op--primary"
        @click="$emit('toggle', activity)"
      >
        <el-icon :size="15"><VideoPlay /></el-icon><span>开启投放</span>
      </button>

      <!-- 暂停：仅投放中/已满员（过期态不提供 —— 它已经不在广场了，暂停没有对象） -->
      <button
        v-if="status === 'LAUNCHING' || status === 'FULL'"
        type="button"
        class="mktcard__op"
        @click="$emit('toggle', activity)"
      >
        <el-icon :size="15"><VideoPause /></el-icon><span>暂停</span>
      </button>

      <!-- 重新激活：过期态（需刷新 expire_time） -->
      <button
        v-if="status === 'EXPIRED'"
        type="button"
        class="mktcard__op mktcard__op--primary"
        @click="$emit('reactivate', activity)"
      >
        <el-icon :size="15"><RefreshRight /></el-icon><span>重新激活</span>
      </button>

      <!--
        分隔线已移除（2026-10-21）：
        操作行在手机两列下会折行，1px 竖线会孤立挂在第一行末尾。
        删除按钮本身用红色区分，语义已足够。
      -->

      <button type="button" class="mktcard__op" @click="$emit('edit', activity)">
        <el-icon :size="15"><EditPen /></el-icon><span>编辑</span>
      </button>

      <button
        type="button"
        class="mktcard__op mktcard__op--danger"
        @click="$emit('delete', activity)"
      >
        <el-icon :size="15"><Delete /></el-icon><span>删除</span>
      </button>
    </div>
  </div>
</template>

<script setup>
/**
 * MktCard.vue —— 集市活动卡片
 *
 * 数据来自 MktActivityVO:category/subTag/title/content/location/price/
 * targetNum/isEnable/isFull/expireTime/(my:status/nickname)。
 * 广场行无 status,my 行有服务端推导的 4 态 status(LAUNCHING/FULL/PAUSED/EXPIRED)。
 */
import { computed } from 'vue'
import { useRemainingCountdown } from '@/composables/useRemainingCountdown'
import { useUserStore } from '@/stores/user'
import {
  Location, Coin, User, Timer, EditPen, VideoPause, VideoPlay, Medal, RefreshRight, Delete,
} from '@element-plus/icons-vue'

const props = defineProps({
  activity: { type: Object, required: true },
  /** 'plaza' 纯展示 | 'my' 状态灯 + 操作组 */
  variant: { type: String, default: 'plaza' },
})

defineEmits(['open', 'edit', 'toggle', 'full', 'reactivate', 'delete'])

const userStore = useUserStore()

/**
 * 是否我自己发的（2026-10-21 加）。
 *
 * 用户实测反馈：「广场上我发布的活动我都认不出是我自己发布的」。
 * 广场卡片此前没有任何归属标记，滑到自己发的也看不出来，
 * 更没法快速判断「这条还要不要管」。
 *
 * 纯前端判断 userId 即可 —— MktActivityVO 一直有 userId 字段，
 * 不需要后端改动。owner 为 null（未登录/异常）时不显示，避免误标。
 */
const isMine = computed(() => {
  const me = userStore.userInfo?.id
  const owner = props.activity.userId
  return me != null && owner != null && String(me) === String(owner)
})

/** 是否已满员。满员后活动仍在广场（后端已去掉 is_full 过滤），但要标记出来 */
const isFull = computed(() => Number(props.activity.isFull) === 1)

/* ---------------- 倒计时(1s,≤2h 橙红) ---------------- */
const { remainingText: timerText, warn: timerWarn } = useRemainingCountdown(
  () => props.activity.expireTime)

/* ---------------- 状态灯文案 ---------------- */
const STATUS_TEXT = { LAUNCHING: '投放中', FULL: '满员', PAUSED: '暂停', EXPIRED: '过期' }
const status = props.activity.status || 'LAUNCHING'
const statusText = STATUS_TEXT[status] || status

/* ---------------- 价格/人数文案 ---------------- */
/** 0 与空都显示「免费」;有值显示「¥x」 */
const priceText = (() => {
  const p = props.activity.price
  if (p == null || Number(p) === 0) return '免费'
  return `¥${Number(p)}`
})()

/* ---------------- 标题（2026-10-21 改读 title） ---------------- */
/**
 * 卡片标题。
 *
 * 【为什么改读 title 而不是 mainText】
 * MktActivityVO 有两个字段：
 *   title    —— 用户实际写的标题（后端 optionalTitle 校验，上限 25 字）
 *   mainText —— derivedTitle(category, subTag) 派生的「分类 · 游戏」主文案
 * 前端一直用 mainText，于是用户写的标题**根本没显示过**，卡片上永远是
 * 分类名的重复（这也是之前 need showTitle 去重的原因）。
 *
 * 现在发布页已经能填标题，改为 title 优先；旧数据（title 是派生值、
 * 或压根没填）仍会落到 mainText，再由 showTitle 判断是否重复。
 */
const titleText = computed(
  () => props.activity.title || props.activity.mainText || '',
)

/**
 * 标题与分类标签重复时不渲染，那行字没有新信息。
 * 覆盖两种情况：旧数据把分类当标题存（title === category），
 * 以及没填标题时后端派生出的「分类 · 副标签」。
 */
const showTitle = computed(() => {
  const t = titleText.value.trim()
  if (!t) return false
  const cat = String(props.activity.category || '').trim()
  const sub = String(props.activity.subTag || '').trim()
  // 与「分类」全等 → 重复
  if (cat && t === cat) return false
  // 与「分类 · 副标签」全等 → 重复（后端的两段式拼法，间隔符是 " · "）
  if (cat && sub && t === `${cat} · ${sub}`) return false
  return true
})
</script>

<style scoped src="./MktCard.css"></style>
