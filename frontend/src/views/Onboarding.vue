<template>
  <!--
    新用户引导 3 步(Onboarding.vue.md)
    路由 /onboarding,meta: { requiresAuth, hideTabBar, 无 back }

    本页最容易做错的三点:
    1) `/onboarding/complete` **每一步都要调**,不是只在最后调(§7)。
       只在最后调的后果:用户在第 2 步杀 App,后端没有 complete 记录,
       下次进首页又被守卫拦回引导页,陷入死循环。实现在 useOnboardingFlow。
    2) 第 1 步**不可跳过**(AC-18):不渲染「跳过」,且拦系统返回手势。
    3)「跳过第 2/3 步」不是退出引导,而是**直接完成并进首页**(§1.4)。
  -->
  <div class="ob">
    <!-- 行 1:进度条,通栏 4px -->
    <div
      class="ob__bar"
      role="progressbar"
      :aria-valuenow="step"
      aria-valuemin="1"
      :aria-valuemax="TOTAL_STEPS"
      aria-label="引导进度"
    >
      <span class="ob__bar-fill" :style="{ width: `${(step / TOTAL_STEPS) * 100}%` }" />
    </div>

    <!-- 行 2:步骤指示 + 跳过(第 1 步不渲染) -->
    <div class="ob__steps">
      <span class="ob__steps-text" aria-live="polite">第 {{ step }} 步 / 共 {{ TOTAL_STEPS }} 步</span>
      <button
        v-if="step > 1"
        type="button"
        class="ob__skip"
        :aria-label="skipAria"
        @click="onSkip"
      >
        {{ skipLabel }}
      </button>
    </div>

    <!-- 行 3:品牌字。桌面端才出现(移动端顶栏已有品牌) -->
    <p class="ob__brand" aria-hidden="true">友声</p>

    <main class="ob__body">
      <!--
        步骤切换:横向位移 + 淡入,**禁止纵向位移**(§4)——
        纵向位移会让上方进度条与下方吸底栏之间产生视差错位。
        key 用 step 强制重建,是刻意的:横向进出场需要 enter/leave 都跑。
      -->
      <Transition :name="dir > 0 ? 'slide-left' : 'slide-right'" mode="out-in">
        <StepIdentity
          v-if="step === 1"
          ref="step1Ref"
          v-model="identity"
          :invalid="identityInvalid"
        />

        <StepTags
          v-else-if="step === 2"
          :groups="tagGroups"
          :picked="tagIds"
          :loading="tagLoading"
          :failed="tagFailed"
          :min-select="minSelect"
          :max-select="maxSelect"
          @toggle="toggleTag"
          @retry="loadTags"
          @skip="finish"
        />

        <StepFollow
          v-else
          :users="users"
          :circles="circles"
          :picked-user-ids="userIds"
          :picked-circle-ids="circleIds"
          :loading="recLoading"
          :users-failed="usersFailed"
          :circles-failed="circlesFailed"
          @pick-user="pickUser"
          @pick-circle="pickCircle"
        />
      </Transition>
    </main>

    <!-- 行 5:吸底操作栏。三步共用一个,避免各写一遍导致安全区高度漂移 -->
    <footer class="ob__foot">
      <p v-if="submitError" class="ob__foot-err" role="alert">{{ submitError }}</p>

      <div class="ob__foot-row">
        <span v-if="step === 2" class="ob__foot-meta">
          已选 {{ tagIds.length }} 个(至少 {{ minSelect }} 个)
        </span>
        <span v-else-if="step === 3" class="ob__foot-meta">
          已关注 {{ userIds.length }} 人 · 已加入 {{ circleIds.length }} 个圈子
        </span>

        <!--
          第 3 步的按钮文案是「进友声」不是「完成」(§1.3):
          这一步的语义是「你可以开始用了」,不是「你提交了一份表单」。
        -->
        <button
          type="button"
          class="ob__primary"
          :disabled="!primaryEnabled"
          @click="onPrimary"
        >
          {{ submitting ? '提交中' : primaryLabel }}
        </button>
      </div>
      <SafeArea position="bottom" />
    </footer>
  </div>
</template>

<script setup>
/**
 * 引导页外壳:进度 + 步骤切换 + 吸底栏。
 * 提交流(§7 每步 complete、字典失效分支、userInfo 刷新)在 useOnboardingFlow,
 * 第 2/3 步的数据与失败降级在 useOnboardingData,三步的表单各自一个组件。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { useUserStore } from '@/stores/user'
import { TOTAL_STEPS, IDENTITY_MODEL } from '@/constants/schoolDict'
import { useOnboardingData, toggleIn } from '@/composables/useOnboardingData'
import { useOnboardingFlow } from '@/composables/useOnboardingFlow'
import SafeArea from '@/components/mobile/SafeArea.vue'
import StepIdentity from '@/components/onboarding/StepIdentity.vue'
import StepTags from '@/components/onboarding/StepTags.vue'
import StepFollow from '@/components/onboarding/StepFollow.vue'

const router = useRouter()
const userStore = useUserStore()

const step = ref(1)
const dir = ref(1) // 1=前进 -1=后退,决定过渡方向
const step1Ref = ref(null)

/**
 * 学校归属。字段名与请求体键名一致(见 useOnboardingFlow.persist),
 * 所以不需要额外一层名字映射 —— 少一层映射就少一个能写错的地方。
 *
 * deptId / gradeId 是**字典 id**,不是自由文本:自由文本会让「计算机学院」
 * 与「计科」算两个院系,同院系推荐(AC-14)漏人。
 */
const identity = ref({ ...IDENTITY_MODEL })

/* 第 2/3 步数据(标签 + 推荐)与失败降级语义都在 composable 里 */
const {
  tagIds, tagGroups, tagLoading, tagFailed, minSelect, maxSelect,
  loadTags, toggleTag, clearTags,
  users, circles, userIds, circleIds, recLoading, usersFailed, circlesFailed,
  loadRecommend, clearFollows,
} = useOnboardingData(() => goto(3))

/**
 * 当前步能否提交。第 1 步由子组件暴露的 valid 决定(它要问 SchoolFields
 * 三个下拉都选上没有);第 3 步恒为 true —— openapi 说 followUserIds 可为空。
 */
const canAdvance = computed(() => {
  if (step.value === 1) return !!step1Ref.value?.valid
  if (step.value === 2) return tagIds.value.length >= minSelect.value
  return true
})

function goto(n) {
  dir.value = n > step.value ? 1 : -1
  step.value = n
}

const {
  submitting, submitError, identityInvalid,
  primaryLabel, primaryEnabled, onPrimary, onSkip,
} = useOnboardingFlow({
  step, identity, tagIds, userIds,
  canAdvance,
  goto,
  loadTags,
  loadRecommend,
  clearTags,
  clearFollows,
})

const skipLabel = computed(() => (step.value === 2 ? '以后再选' : '先逛逛'))
const skipAria = computed(() =>
  step.value === 2 ? '跳过兴趣标签,以后再选' : '跳过关注,直接进友声',
)

function pickUser(id) {
  toggleIn(userIds.value, id)
}

function pickCircle(id) {
  toggleIn(circleIds.value, id)
}

/** 供 useOnboardingData 回调:推荐数据回来后跳第 3 步 */
function finish() {
  goto(3)
}

/* ---------------- 返回手势拦截(AC-18) ---------------- */

/**
 * 第 1 步拦截系统返回(安卓物理键 / iOS 边缘手势):
 * 弹一次提示并**把历史记录补回去**,否则用户连按几次返回就把引导页退掉了。
 * 实现方式:pushState 一个哨兵,popstate 时再 push 回去。
 */
function onPopState() {
  if (step.value > 1) {
    // 第 2/3 步允许正常返回上一步
    if (step.value === 3) goto(2)
    return
  }
  history.pushState(null, '', location.href)
  ElMessage({ message: '先选完学校和年级,就一步', type: 'info' })
}

onMounted(() => {
  // 已完成引导的用户访问 /onboarding:重定向到 /(§3 权限不足,不是报错)
  if (userStore.userInfo?.profileComplete) {
    router.replace('/')
    return
  }
  history.pushState(null, '', location.href)
  window.addEventListener('popstate', onPopState)
})

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopState)
})
</script>

<style scoped src="./Onboarding.css"></style>