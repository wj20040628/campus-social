<template>
  <!--
    引导第 1 步:选学校 / 学院 / 年级(Onboarding.vue.md §1.1)
    **不可跳过**(AC-18):父组件不渲染「跳过」按钮,且拦返回手势。
    本组件只负责标题与校验,不含任何「下一步」按钮 —— 吸底栏由父组件统一渲染,
    否则三步各写一遍吸底栏,安全区与按钮高度必然漂移。

    三个下拉本体在 SchoolFields 里,与注册页共用同一份实现。
  -->
  <div class="step1">
    <header class="step1__head">
      <h1 class="step1__title">先说清楚你是哪个学校的</h1>
      <p class="step1__sub">认证之后才能发动态和搭话</p>
    </header>

    <SchoolFields
      ref="fieldsRef"
      v-model="form"
      :invalid="invalid"
      show-field-error
      @select="touch"
    />
  </div>
</template>

<script setup>
/**
 * v-model 双向绑定整个归属对象 { schoolId, deptId, gradeId }。
 * 父组件持有它,因为**每一步**都要把当前归属带给 /onboarding/complete
 * (Onboarding.vue.md §7:每步都调,不是只在最后调)。
 */
import { ref } from 'vue'
import SchoolFields from '@/components/common/SchoolFields.vue'

const form = defineModel({ type: Object, required: true })

/**
 * 服务端判定的无效字段 { dept?, grade? }。
 *
 * 为什么需要这一层透传:引导第 1 步点「下一步」会调 /onboarding/complete,
 * 后端对已停用的学院/年级返 1040/1041。本地校验**全过**(id 有值、格式对),
 * 只有服务端知道失效 —— 不透传的话就是一次沉默失败:
 * toast 一闪而过,表单静默停在第 1 步,用户不知道要动哪个下拉。
 * 值由父组件从错误码映射而来(见 utils/dictError.js)。
 */
defineProps({
  invalid: { type: Object, default: () => ({}) },
})

const fieldsRef = ref(null)

/** 用户碰过的字段。用于「漏了哪个」的字段级提示,没碰过的不追问 */
const touched = ref({ dept: false, grade: false })

function touch(key) {
  if (key in touched.value) touched.value = { ...touched.value, [key]: true }
}

defineExpose({
  /** 三项都填了才可点「下一步」(AC-18 阻断点) */
  get valid() {
    return !!fieldsRef.value?.isValid()
  },
})
</script>

<style scoped>
.step1 {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-6);
}

.step1__head {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.step1__title {
  margin: 0;
  font-size: var(--ys-text-2xl);
  line-height: var(--ys-lh-2xl);
  /* 24px 属标题档,负字距 */
  letter-spacing: var(--ys-ls-2xl);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.step1__sub {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-muted);
}
</style>