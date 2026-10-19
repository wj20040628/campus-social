<template>
  <!--
    学校归属五字段(学校 / 学院 / 本专科 / 专业 / 年级),引导第 1 步与注册页共用。
    抽成组件的原因:两处的字段、下拉规格、错误提示、44px 热区完全一致,
    各自写一遍必然出现「一处 filterable 一处不 filterable」的漂移。

    字典下拉而非自由文本:自由文本会让「计算机学院」与「计科」算两个院系,
    同院系推荐(AC-14)漏人(用户已拍板)。

    级联关系:专业依赖「已选学院 + 已选本专科」两个前置值,学院或本专科一变,
    已选专业立即失效并重新拉取可选专业(见脚本 set())。
  -->
  <div class="school-fields">
    <el-form-item label="学校" required>
      <el-select
        :model-value="model.schoolId"
        class="school-fields__ctl"
        placeholder="选学校"
        @update:model-value="set('schoolId', $event)"
      >
        <el-option v-for="s in SCHOOLS" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
    </el-form-item>

    <el-form-item label="学院" required :error="fieldError.dept">
      <el-select
        :model-value="model.deptId"
        class="school-fields__ctl"
        :placeholder="deptPlaceholder"
        :filterable="dict.deptSearchable"
        :loading="dict.loading"
        :disabled="dict.loading"
        @update:model-value="set('deptId', $event)"
      >
        <el-option v-for="d in dict.deptOptions" :key="d.id" :label="d.name" :value="d.id" />
      </el-select>
    </el-form-item>

    <el-form-item label="本专科" required :error="fieldError.level">
      <el-select
        :model-value="model.level"
        class="school-fields__ctl"
        placeholder="选本科或专科"
        :disabled="dict.loading"
        @update:model-value="set('level', $event)"
      >
        <el-option v-for="o in LEVEL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>
    </el-form-item>

    <el-form-item label="专业" required :error="fieldError.major">
      <el-select
        :model-value="model.majorId"
        class="school-fields__ctl"
        :placeholder="majorPlaceholder"
        :filterable="majorOptions.length > 12"
        :loading="majorLoadingNow"
        :disabled="!model.deptId || !model.level || majorLoadingNow"
        @update:model-value="set('majorId', $event)"
      >
        <el-option v-for="m in majorOptions" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
    </el-form-item>

    <!--
      专业加载中的明确提示。el-select 内部的 loading 小转圈 + 禁用态容易被误认为卡死,
      这里额外给一行旋转图标 + 文案,用户一眼能看出「在加载」而不是「坏了」。
    -->
    <p v-if="majorLoadingNow" class="school-fields__loading">
      <el-icon class="school-fields__spin"><Loading /></el-icon>
      <span>正在加载专业…</span>
    </p>

    <p v-if="showMajorRetry" class="school-fields__retry">
      <WarningFilled class="school-fields__retry-icon" aria-hidden="true" />
      <span>专业列表没拉出来，</span>
      <button type="button" class="school-fields__retry-btn" @click="dict.ensureMajorsLoaded(true)">
        点这里重试
      </button>
    </p>

    <el-form-item label="年级" required :error="fieldError.grade">
      <el-select
        :model-value="model.gradeId"
        class="school-fields__ctl"
        :placeholder="gradePlaceholder"
        :loading="dict.loading"
        :disabled="dict.loading"
        @update:model-value="set('gradeId', $event)"
      >
        <el-option v-for="g in dict.gradeOptions" :key="g.id" :label="g.name" :value="g.id" />
      </el-select>
    </el-form-item>

    <!--
      字典不可用时的出口。给「为什么下拉是灰的」一个答案 + 一个重试,
      而不是让用户对着空下拉发呆(AC-19:不能只有状态没有出口)。
    -->
    <p v-if="showRetry" class="school-fields__retry">
      <WarningFilled class="school-fields__retry-icon" aria-hidden="true" />
      <span>院系列表没拉出来，</span>
      <button type="button" class="school-fields__retry-btn" @click="dict.ensureLoaded(true)">
        点这里重试
      </button>
    </p>
  </div>
</template>

<script setup>
/**
 * v-model 双向绑定整个归属对象 { schoolId, deptId, level, gradeId, majorId }。
 * 父组件持有它,因为引导页**每一步**都要把当前归属带给 /onboarding/complete
 * (Onboarding.vue.md §7:每步都调,不是只在最后调)。
 */
import { computed, onMounted, ref } from 'vue'
import { WarningFilled, Loading } from '@element-plus/icons-vue'
import { SCHOOLS, IDENTITY_MODEL, LEVEL_OPTIONS, DICT_FIELDS } from '@/constants/schoolDict'
import { useDictStore } from '@/stores/dict'

const props = defineProps({
  /**
   * 字段级错误提示的开关,由调用方决定要不要追问。
   * 引导页要(填了一半漏了要告诉),设置页不一定要(随时能改,不用啰嗦)。
   */
  showFieldError: { type: Boolean, default: false },
  /**
   * 服务端判定的**无效字段** { dept?:string, grade?:string, level?:string, major?:string }。
   *
   * 为什么需要它:后端 1040/1041/1057 是「你选的学院/年级/专业已停用」——
   * 本地校验**全过**了(id 格式对、字段非空),只有服务端知道它已失效。
   * 没有这个通道的话,request.js 弹一个 toast 就没了,表单静默不动,
   * 用户不知道该动哪个下拉。
   *
   * 值由调用方从错误码映射而来(见 utils/dictError.js),本组件不认错误码。
   */
  invalid: { type: Object, default: () => ({}) },
})

const emit = defineEmits(['select'])

const model = defineModel({ type: Object, required: true })
const dict = useDictStore()

/** 用户碰过的字段。只有碰过还没选的才追问,没碰过的不打扰 */
const touched = ref({ dept: false, grade: false, level: false, major: false })

/**
 * 用户已经重选过的字段。
 *
 * 为什么要在组件内记而不用 emit 让父组件清 props:
 * 三个调用方(Onboarding / Register / Settings)都会忘,漏一个就是
 * 「服务端说学院已停用 → 用户重选了 → 提示还挂着」,比不提示更糟。
 * 组件自己知道用户碰过哪个字段,不依赖调用方记忆。
 */
const repicked = ref({ dept: false, grade: false, level: false, major: false })

function set(key, val) {
  const next = { ...model.value, [key]: val }
  // 学院或本专科变了:已选专业必然失效(专业属于旧的「学院+层次」组合),
  // 立即清空并重新拉取可选专业,否则会留下一个根本不在新列表里的旧 id。
  if (key === 'deptId' || key === 'level') {
    next.majorId = null
    if (key === 'deptId') repicked.value.dept = true
    if (key === 'level') repicked.value.level = true
    model.value = next
    triggerMajorLoad()
    emit('select', key)
    return
  }
  if (key === 'gradeId') repicked.value.grade = true
  if (key === 'majorId') repicked.value.major = true
  model.value = next
  // 无论选中还是取消都上报:父组件据此知道「用户碰过这一项」,
  // 只有碰过还没选的字段才追问,没碰过的不打扰
  emit('select', key)
}

/**
 * 触发专业下拉就绪:确保全校专业已拉取(挂载时一次拉齐,这里通常命中已加载、无网络)。
 * 专业下拉改为纯前端按「当前学院 + 本专科」过滤,所以选任意字段组合都即时出专业,
 * 不再有「首次选完学院+本专科却没加载出来」的问题。
 */
function triggerMajorLoad() {
  dict.ensureMajorsLoaded()
}

/**
 * 当前专业下拉选项:按「当前选中的学院 + 本专科」从已预取的全校专业里过滤。
 * 纯前端同步过滤,随当前选择即时变化,无网络、无竞态(彻底消除选 A 院显示 B 院专业)。
 */
const majorOptions = computed(() => dict.majorOptionsFor(model.value.deptId, model.value.level))

/** 全校专业首次拉取中(挂载时)——专业下拉据此显示加载动画;加载完即按当前选择即时过滤 */
const majorLoadingNow = computed(() => dict.majorsLoading)

/** 全校专业拉取失败且不在加载中:给一个重试入口 */
const showMajorRetry = computed(() => !!dict.majorsError && !dict.majorsLoading)

/** 字典在加载中 / 失败降级时,placeholder 要说清为什么不能选 */
const deptPlaceholder = computed(() =>
  dict.loading ? '正在取院系列表' : dict.degraded ? '暂时取不到院系列表' : '选学院',
)
const gradePlaceholder = computed(() =>
  dict.loading ? '正在取年级' : dict.degraded ? '暂时取不到年级' : '选年级',
)
const majorPlaceholder = computed(() => {
  if (!model.value.deptId) return '先选学院'
  if (!model.value.level) return '先选本专科'
  if (majorLoadingNow.value) return '正在取专业列表'
  return '选专业'
})

/** 只有「已经尝试过拉取」且失败时才提示重试 —— 首屏加载中不打扰 */
const showRetry = computed(() => dict.degraded && !dict.loading)

/**
 * 字段级错误。**服务端判定的无效优先于本地「漏了没填」**:
 * 服务端说「这个专业已停用」时,提示必须是这句 —— 改本地校验只会
 * 让用户以为「我重新选一个就行」,然后在同一个失效选项上再失败一次。
 */
const fieldError = computed(() => {
  const invalid = props.invalid || {}
  // 服务端判定了就用它,不管 showFieldError —— 那是**已经发生的失败**,
  // 不是「可能漏了」的预防提示,不该被开关关掉。
  // 但用户重选过就撤下:提示的目的是让人去改,改了还挂着就是噪音。
  const dept = invalid.dept && !repicked.value.dept ? invalid.dept : ''
  const grade = invalid.grade && !repicked.value.grade ? invalid.grade : ''
  const level = invalid.level && !repicked.value.level ? invalid.level : ''
  const major = invalid.major && !repicked.value.major ? invalid.major : ''
  if (dept || grade || level || major) return { dept, grade, level, major }

  if (!props.showFieldError) return { dept: '', grade: '', level: '', major: '' }
  return {
    dept: touched.value.dept && !model.value.deptId ? '选一个学院' : '',
    grade: touched.value.grade && !model.value.gradeId ? '选一个年级' : '',
    level: touched.value.level && !model.value.level ? '选一个本专科' : '',
    major: touched.value.major && !model.value.majorId ? '选一个专业' : '',
  }
})

/** 五项都有值才算有效。schoolId 恒有值仍一起判,防御性而非冗余 */
function isValid(m = model.value) {
  return !!(m.schoolId && m.deptId && m.level && m.gradeId && m.majorId)
}

onMounted(() => {
  // 学校单校,预选省一次点击。只在为空时设,避免覆盖后端回填的既有值。
  if (!model.value.schoolId) {
    model.value = { ...IDENTITY_MODEL, ...model.value, schoolId: SCHOOLS[0].id }
  }
  dict.ensureLoaded()
  // 挂载即预取全校专业,之后专业下拉纯前端按「学院 + 本专科」过滤,选中即刻出选项
  dict.ensureMajorsLoaded()
  // 回填场景:既有归属带 deptId+level,确保全校专业已就绪以正确回显已选专业
  if (model.value.deptId && model.value.level) {
    triggerMajorLoad()
  }
})

defineExpose({ isValid })
</script>

<style scoped>
/*
  栅格一律 CSS Grid:EP 内置断点 768/992/1200/1920 与设计系统的
  640/768/1024/1280/1536 错位,el-col 的 :xs/:sm/:md 禁用(SPEC §10.3 第3 条)。
  本项目构建链无 postcss-custom-media,`@media (--ys-bp-*)` 会被浏览器静默丢弃,
  故写字面量,值仍取自 design-tokens.json 的 breakpoint 段。
  375px 下五个下拉各占满一行。
*/
.school-fields {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--ys-space-5);
}

.school-fields__ctl {
  width: 100%;
}

/* iOS Safari:<16px 的输入控件聚焦时页面会被放大且不会自动缩回 */
.school-fields :deep(.el-select__input) {
  font-size: var(--ys-text-md);
}

.school-fields__retry {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-danger);
}

.school-fields__retry-icon {
  width: 1em;
  height: 1em;
  flex-shrink: 0;
}

/* 专业加载中的旋转图标 + 文案:明确告诉用户「在加载」而非「卡死」 */
.school-fields__loading {
  display: flex;
  align-items: center;
  gap: var(--ys-space-1);
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-text-muted, #8a8a8a);
}

.school-fields__spin {
  width: 1em;
  height: 1em;
  flex-shrink: 0;
  animation: school-fields-spin 0.9s linear infinite;
}

@keyframes school-fields-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

/* 文字按钮但热区撑到 44px:视觉上是行内文字,点的时候要好按 */
.school-fields__retry-btn {
  min-height: var(--ys-touch-min);
  padding: 0 var(--ys-space-1);
  border: 0;
  background: none;
  font-family: var(--ys-font-body);
  font-size: inherit;
  color: var(--ys-danger);
  text-decoration: underline;
  cursor: pointer;
}

@media (min-width: 768px) {
  .school-fields {
    /* 768 起两列;五项时最后一项落单,靠 grid 而不是靠 br 换行 */
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
