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

    <!-- 学号核验(选填):填了学号+姓名后两位,第 1 步就会顺路提交核验,
         不填也不阻塞(之后在设置页补)。学号规则:13~14 位数字,
         班级只能 0X,最后两位是班内排名(≤70)。 -->
    <div class="step1__verify">
      <p class="step1__verify-title">校内认证（选填）</p>
      <p class="step1__verify-sub">填了学号和姓名后两位，就能直接完成认证，不用再跑去设置页</p>
      <el-input
        v-model="form.studentNo"
        placeholder="学号，如 23080901060536"
        maxlength="14"
        class="step1__input"
        @input="onStudentNoInput"
      />
      <el-input
        v-model="form.nameTail"
        placeholder="姓名后两位，如「张三」填「三」"
        maxlength="4"
        class="step1__input"
      />
      <p v-if="studentNoHint" class="step1__verify-hint">{{ studentNoHint }}</p>
    </div>
  </div>
</template>

<script setup>
/**
 * v-model 双向绑定整个归属对象 { schoolId, deptId, gradeId }。
 * 父组件持有它,因为**每一步**都要把当前归属带给 /onboarding/complete
 * (Onboarding.vue.md §7:每步都调,不是只在最后调)。
 */
import { ref, computed } from 'vue'
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

/** 学号格式的即时提示(宽松:非空才提示,精确校验在后端) */
const studentNoHint = computed(() => {
  const sn = (form.value.studentNo || '').trim()
  if (!sn) return ''
  if (!/^\d+$/.test(sn)) return '学号需为纯数字'
  if (sn.length !== 13 && sn.length !== 14) return '学号需为 13~14 位数字'
  return ''
})

function onStudentNoInput() {
  // 占位:让 @input 有语义,提示由 computed 驱动
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

.step1__verify {
  display: flex;
  flex-direction: column;
  gap: var(--ys-space-2);
}

.step1__verify-title {
  margin: 0;
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.step1__verify-sub {
  margin: 0 0 var(--ys-space-1);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.step1__input :deep(.el-input__inner) {
  font-size: var(--ys-text-md);
}

.step1__verify-hint {
  margin: 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-danger);
}
</style>