<template>
  <!--
    引导第 2 步:选 3-5 个兴趣标签(Onboarding.vue.md §1.2)
    设计明确要求:选中态用 --ys-accent-soft 而**不是** --ys-cat-* 四类配色。
    理由(§1.2 原文):--ys-cat-* 是「找搭子列表里的标签」专用,
    在选择界面里同时出现四套颜色是视觉噪音。
  -->
  <div class="step2">
    <header class="step2__head">
      <h1 class="step2__title">选几个你感兴趣的</h1>
      <p class="step2__sub">选 {{ min }} 到 {{ max }} 个,之后找搭子就是按这个匹配的</p>
    </header>

    <!-- 标签拉取失败:整步降级,但**不阻塞引导完成**(给「以后再选」出口) -->
    <div v-if="failed" class="step2__err">
      <p class="step2__err-text">标签没拉出来</p>
      <div class="step2__err-act">
        <button type="button" class="btn btn--primary" @click="$emit('retry')">重试</button>
        <button type="button" class="btn" @click="$emit('skip')">以后再选</button>
      </div>
    </div>

    <!-- 骨架:chip 块宽度参差,不用统一宽度(整齐的 chip 网格不像标签云) -->
    <div v-else-if="loading" class="step2__cloud" aria-hidden="true">
      <span
        v-for="(w, i) in skeletonWidths"
        :key="i"
        class="step2__chip step2__chip--skeleton"
        :style="{ width: w }"
      />
    </div>

    <div v-else class="step2__cloud-wrap">
      <section v-for="g in groups" :key="g.category" class="step2__group">
        <h2 class="step2__group-title">{{ g.categoryName }}</h2>
        <div class="step2__cloud">
          <button
            v-for="t in g.tags"
            :key="t.id"
            type="button"
            class="step2__chip"
            :class="{
              'is-on': picked.includes(t.id),
              // 选满 max 后未选 chip 变禁用但**不隐藏**(隐藏会让用户以为标签变少了)
              'is-off': atMax && !picked.includes(t.id),
            }"
            :aria-pressed="picked.includes(t.id)"
            :aria-label="`兴趣标签 ${t.name}${picked.includes(t.id) ? ',已选中' : ''}`"
            :disabled="atMax && !picked.includes(t.id)"
            @click="toggle(t.id)"
          >
            <span class="step2__chip-text">{{ clip(t.name) }}</span>
            <el-icon v-if="picked.includes(t.id)" :size="16" class="step2__chip-check">
              <Check />
            </el-icon>
          </button>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * 标签多选(SPEC §5.2 / Onboarding.vue.md §1.2)
 *
 * 两个容易做错的点:
 * 1) min/max **由后端下发**(openapi 的 OnboardingTagsResponse.minSelect/maxSelect),
 *    前端只兜底不覆盖 —— 前端写死就是「沉默逻辑错误」。
 * 2) 选到第 6 个时**取消最旧的一个**并 toast,而不是拒绝选中。
 *    直接拒绝会让用户以为坏了;自动去掉最旧的则符合「最近兴趣」的心理模型。
 */
import { computed } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { Check } from '@element-plus/icons-vue'
import { FALLBACK_MIN_SELECT, FALLBACK_MAX_SELECT } from '@/constants/schoolDict'

const props = defineProps({
  groups: { type: Array, default: () => [] },
  picked: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  failed: { type: Boolean, default: false },
  minSelect: { type: Number, default: 0 },
  maxSelect: { type: Number, default: 0 },
})

const emit = defineEmits(['toggle', 'retry', 'skip'])

const min = computed(() => props.minSelect || FALLBACK_MIN_SELECT)
const max = computed(() => props.maxSelect || FALLBACK_MAX_SELECT)
const atMax = computed(() => props.picked.length >= max.value)

/**
 * 选满后的溢出处理在**父组件**:只有父组件持有 picked 数组、才知道
 * 「去掉最旧的一个」该去哪个。本组件只发意图 + 自己发 toast。
 *
 * toast 的判定条件是「本次点击会溢出」,不是「当前已满」——
 * 写反的话用户每点一个已满的 chip 都会收到一次「已经帮你去掉一个」,
 * 而他实际上一个都没选上(因为 disabled 拦住了)。
 */
const skeletonWidths = ['72px', '96px', '64px', '88px', '76px', '60px', '92px', '68px', '84px', '72px', '88px', '64px']

function clip(name) {
  // 标签名最多 6 字(Onboarding.vue.md §3 内容超长)。截断加省略号,不缩字距。
  return name.length > 6 ? `${name.slice(0, 6)}…` : name
}

function toggle(id) {
  const on = props.picked.includes(id)
  // 未选 + 已达上限 = 本次点击会溢出第 6 个
  if (!on && atMax.value) {
    ElMessage({ message: `最多 ${max.value} 个,已经帮你去掉一个`, type: 'info' })
  }
  emit('toggle', id)
}
</script>

<style scoped>
.step2 {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-6);
}

.step2__head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.step2__title {
  margin: 0;
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.step2__sub {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}

.step2__group + .step2__group {
  margin-top: var(--ys-space-5);
}

.step2__group-title {
  margin: 0 0 var(--ys-space-3);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.step2__cloud {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ys-space-2);
}

.step2__chip {
  /* 视觉 32px,靠纵向 padding 把热区撑到 44px(AC-15) */
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-4);
  border-radius: var(--ys-radius-full);
  border: 1px solid var(--ys-border);
  background: var(--ys-surface);
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  cursor: pointer;
  transition:
    background-color var(--ys-motion-fast) var(--ys-ease-out),
    border-color var(--ys-motion-fast) var(--ys-ease-out),
    color var(--ys-motion-fast) var(--ys-ease-out);
}

.step2__chip.is-on {
  background: var(--ys-accent-soft);
  border-color: var(--ys-accent);
  color: var(--ys-accent);
}

.step2__chip.is-off {
  background: var(--ys-surface-2);
  border-color: var(--ys-border-soft);
  color: var(--ys-meta);
  cursor: not-allowed;
}

.step2__chip:disabled {
  opacity: 1; /* 禁用态由上面的底色/字色表达,不叠加浏览器默认的 0.4 透明度 */
}

.step2__chip-check {
  flex: none;
}

.step2__chip--skeleton {
  display: block;
  height: var(--ys-touch-min);
  padding: 0;
  border-color: transparent;
  background: linear-gradient(
    90deg,
    var(--ys-surface-2) 25%,
    var(--ys-border-soft) 37%,
    var(--ys-surface-2) 63%
  );
  background-size: 400% 100%;
  animation: ys-sheen 1.4s var(--ys-ease-linear) infinite;
  cursor: default;
}

.step2__err {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--ys-space-3);
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border-soft);
  border-radius: var(--ys-radius-lg);
  background: var(--ys-surface-2);
}

.step2__err-text {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

.step2__err-act {
  display: flex;
  gap: var(--ys-space-3);
}

.btn {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-5);
  border-radius: var(--ys-radius-lg);
  border: 1px solid var(--ys-border-control);
  background: var(--ys-surface);
  color: var(--ys-fg-2);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-base);
  cursor: pointer;
}

.btn--primary {
  border-color: var(--ys-accent);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
}

@keyframes ys-sheen {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .step2__chip--skeleton {
    animation: none;
  }
}
</style>
