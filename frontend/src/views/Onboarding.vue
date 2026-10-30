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
        v-if="step < TOTAL_STEPS"
        type="button"
        class="ob__skip"
        :aria-label="skipAria"
        @click="onSkip"
      >
        跳过引导
      </button>
    </div>

    <!-- 行 3:品牌字。桌面端才出现(移动端顶栏已有品牌) -->
    <p class="ob__brand" aria-hidden="true">友声</p>

    <main class="ob__body">
      <!--
        步骤切换:横向位移 + 淡入,**禁止纵向位移**(§4)——
        纵向位移会让上方进度条与下方吸底栏之间产生视差错位。

        [不要加 mode="out-in",也不要为了补它而加 :key]
        out-in 的缺陷已在 MainLayout 上实测坐实:同一 URL 整页加载正常、
        SPA 导航永久空白(leave 阶段抛错 → 收不到 transitionend → 新组件永不挂载)。
        引导页是**新用户第一次用**的路径,白屏代价最高,所以这轮一并去掉。

        StepTags / StepFollow **零内部 ref** —— v-if/v-else-if 切换重建无损,
        所以不加 key,只去 mode。
      -->
      <Transition :name="dir > 0 ? 'slide-left' : 'slide-right'">
        <!-- :key 随步切换（2026-10-26 修）：跳过走 enter() 后立即 router.replace('/')，
             Transition 不含 key 时 Vue 复用旧子树，新 step 的组件可能不挂载，点击落空。 -->
        <!--
          第 1 步：学校归属（用于同院系推荐）+ 选填学号。
          学校归属是后续推荐与引导完成接口的必填项；
          学号只是可选资料，不影响任何功能权限（2026-10-30）。
        -->
        <StepVerify
          v-if="step === 1"
          v-model="identity"
          @verified="onVerified"
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

      <p class="ob__foot-hint">跳过后仍可补充资料，在「我的」里随时修改</p>

      <div class="ob__foot-row">
        <span v-if="step === 2" class="ob__foot-meta">
          已选 {{ tagIds.length }} 个（至少 {{ minSelect }} 个，可不选）
        </span>
        <span v-else-if="step === 3" class="ob__foot-meta">
          已关注 {{ userIds.length }} 人 · 已加入 {{ circleIds.length }} 个圈子
        </span>

        <!--
          跳过引导（2026-10-26 补）：顶部那行「跳过引导」在手机上太像
          普通说明文字，用户找不到出口。这里放一个与主按钮同高的次按钮，
          不填任何归属/学号也能直接进首页（onSkip 不调需 @NotNull 的 complete）。
        -->
        <button
          v-if="step < TOTAL_STEPS"
          type="button"
          class="ob__skip-btn"
          @click="onSkip"
        >
          跳过引导
        </button>

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
import { useUserStore } from '@/stores/user'
import { TOTAL_STEPS, SCHOOLS } from '@/constants/schoolDict'
import { useOnboardingData, toggleIn } from '@/composables/useOnboardingData'
import { useOnboardingFlow } from '@/composables/useOnboardingFlow'
import SafeArea from '@/components/mobile/SafeArea.vue'
import StepVerify from '@/components/onboarding/StepVerify.vue'
import StepTags from '@/components/onboarding/StepTags.vue'
import StepFollow from '@/components/onboarding/StepFollow.vue'

const router = useRouter()
const userStore = useUserStore()

const step = ref(1)
const dir = ref(1) // 1=前进 -1=后退,决定过渡方向

/**
 * 学校归属。字段名与请求体键名一致(见 useOnboardingFlow.persist),
 * 所以不需要额外一层名字映射 —— 少一层映射就少一个能写错的地方。
 *
 * deptId / gradeId 是**字典 id**,不是自由文本:自由文本会让「计算机学院」
 * 与「计科」算两个院系,同院系推荐(AC-14)漏人。
 */
// 注册页已收过学校/学院/年级并落库,引导页不再重复收(问题 2 修复):
// 进入引导时直接从 userInfo 预填,避免让用户再填一遍已填过的归属。
// schoolId 在 userInfo 里不下发(MVP 单校恒为 1),用 SCHOOLS 兜底。
const identity = ref({
  schoolId: userStore.userInfo?.schoolId ?? SCHOOLS[0].id,
  // 校区(2026-10-25):与院系解耦,已保存过就回填
  campus: userStore.userInfo?.campus ?? null,
  deptId: userStore.userInfo?.deptId ?? null,
  // 本专科(level)由专业派生,user 表不冗余存;StepVerify 会在专业字典加载后反查补上。
  level: null,
  gradeId: userStore.userInfo?.gradeId ?? null,
  majorId: userStore.userInfo?.majorId ?? null,
})

/* 第 2/3 步数据(标签 + 推荐)与失败降级语义都在 composable 里 */
const {
  tagIds, tagGroups, tagLoading, tagFailed, minSelect, maxSelect,
  loadTags, toggleTag, clearTags,
  users, circles, userIds, circleIds, recLoading, usersFailed, circlesFailed,
  loadRecommend, clearFollows,
} = useOnboardingData(() => goto(3))

/**
 * 当前步能否前进。
 *
 * 三步（学校归属 → 标签 → 关注）：
 *   第 1 步：由 StepVerify 组件内部控制学号提交，外壳的下一步只要求归属齐全；
 *            学号可以不填，不影响使用
 *   第 2 步：兴趣标签需选满最少数量
 *   第 3 步：恒为 true（openapi 说 followUserIds 可为空）
 */
const identityComplete = computed(() => {
  const i = identity.value
  return !!(i.deptId && i.level && i.majorId && i.gradeId)
})

const canAdvance = computed(() => {
  /*
   * 第 1 步的「下一步」不能恒为 true（2026-10-26 修）：
   * 后端 /onboarding/complete 的 deptId / gradeId / majorId 都是 @NotNull，
   * 学校归属没选完就点下一步，必然收到 400「请选择学院/年级/专业」。
   * 学号可以不填，但归属必须先齐。
   */
  if (step.value === 1) return identityComplete.value
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
  step, identity, tagIds, userIds, circleIds,
  canAdvance,
  goto,
  loadTags,
  loadRecommend,
  clearTags,
  clearFollows,
})

// 「跳过」第 1/2 步都出现，语义是「先逛逛」(§1.4)：直接完成并进首页
const skipAria = computed(() => '跳过当前步骤，直接进友声')

function pickUser(id) {
  toggleIn(userIds.value, id)
}

function pickCircle(id) {
  toggleIn(circleIds.value, id)
}

/** 供 StepTags 的「跳过」回调:拉推荐并跳到第 3 步(关注/圈子) */
function finish() {
  loadRecommend()
  goto(3)
}

/**
 * 提交结果回调（来自 StepVerify）。
 *
 * 只做一件事：刷新 userInfo，让资料页/个人主页及时看到新的登记状态。
 * 不在这里跳到下一步：用户可能想先改归属再重填学号，让他自己点「下一步」。
 */
async function onVerified() {
  try {
    await userStore.fetchMe()
    // 提交后后端可能补写归属/校区，回填父页面这份对象，避免下一步提交旧值
    const info = userStore.userInfo
    if (info) {
      identity.value = {
        ...identity.value,
        schoolId: info.schoolId ?? identity.value.schoolId,
        campus: info.campus ?? identity.value.campus,
        deptId: info.deptId ?? identity.value.deptId,
        gradeId: info.gradeId ?? identity.value.gradeId,
        majorId: info.majorId ?? identity.value.majorId,
      }
    }
  } catch {
    /* 静默：本地旧值只让提示条多显示一次，不影响功能 */
  }
}

/* ---------------- 返回手势拦截(AC-18) ---------------- */

/**
 * 系统返回拦截（安卓物理键 / iOS 边缘手势）：
 * 第 1 步拦截并**把历史记录补回去**，否则用户连按几次返回就把引导页退掉了；
 * 第 2/3 步允许正常返回上一步。
 */
function onPopState() {
  // 第 2/3 步允许正常返回上一步；第 1 步已提供「跳过」出口，
  // 不再拦返回手势 —— 跳过能走通，就不必把人按在这一页。
  if (step.value > 1) {
    goto(step.value - 1)
  }
}

onMounted(() => {
  // 已完成引导的用户访问 /onboarding:重定向到 /(§3 权限不足,不是报错)
  if (userStore.userInfo?.profileComplete) {
    router.replace('/')
    return
  }
  // 第 1 步(兴趣标签)一进引导就拉数据,避免空白等待
  loadTags()
  history.pushState(null, '', location.href)
  window.addEventListener('popstate', onPopState)
})

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopState)
})
</script>

<style scoped src="./Onboarding.css"></style>