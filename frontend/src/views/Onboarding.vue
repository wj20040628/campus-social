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

        [不要加 mode="out-in",也不要为了补它而加 :key]
        out-in 的缺陷已在 MainLayout 上实测坐实:同一 URL 整页加载正常、
        SPA 导航永久空白(leave 阶段抛错 → 收不到 transitionend → 新组件永不挂载)。
        引导页是**新用户第一次用**的路径,白屏代价最高,所以这轮一并去掉。

        StepTags / StepFollow **零内部 ref** —— v-if/v-else-if 切换重建无损,
        所以不加 key,只去 mode。
      -->
      <Transition :name="dir > 0 ? 'slide-left' : 'slide-right'">
        <!--
          2026-10-21 新增第 1 步：学校归属 + 学号认证。
          放在最前面，因为它决定后续功能可用范围（未认证不能发私信/建圈子/
          加圈子/发集市），用户越早知道越不会在点击时被拦。
        -->
        <StepVerify
          v-if="step === 1"
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
        <span v-if="step === 1" class="ob__foot-meta">
          学号可不填，稍后也能认证
        </span>
        <span v-else-if="step === 2" class="ob__foot-meta">
          已选 {{ tagIds.length }} 个（至少 {{ minSelect }} 个）
        </span>
        <span v-else-if="step === 3" class="ob__foot-meta">
          已关注 {{ userIds.length }} 人 · 已加入 {{ circleIds.length }} 个圈子
        </span>

        <!--
          第 1 步（认证）没有「跳过」：跳过就等于保持未认证，
          用户照样能用（能浏览、能发帖），只是四类动作被限制。
          与其给一个「跳过」按钮，不如让「保存归属但不填学号」自然成为跳过。
        -->
        <button
          v-if="step === 2"
          type="button"
          class="ob__skip"
          @click="finish"
        >跳过</button>
        <button
          v-if="step === 2"
          type="button"
          class="ob__skip ob__skip--all"
          @click="onSkip"
        >一键跳过</button>

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
  deptId: userStore.userInfo?.deptId ?? null,
  // 本专科(level)由专业派生,user 表不冗余存;这里只为级联下拉过滤维度,预填为空。
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
 * 2026-10-21 三步（认证 → 标签 → 关注）：
 *   第 1 步：由 StepVerify 组件内部控制提交，外壳的「下一步」恒可用 ——
 *            用户可以不填学号直接走（那就是「先浏览、后认证」的路径）
 *   第 2 步：兴趣标签需选满最少数量
 *   第 3 步：恒为 true（openapi 说 followUserIds 可为空）
 */
const canAdvance = computed(() => {
  if (step.value === 1) return true
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

// 跳过只出现在第 2 步(关注/圈子),语义是「先逛逛」(§1.4):直接完成并进首页
const skipLabel = computed(() => '先逛逛')
const skipAria = computed(() => '跳过关注,直接进友声')

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
 * 认证结果回调（2026-10-21 新增，来自 StepVerify）。
 *
 * 只做一件事：刷新 userInfo。
 * 因为「已认证 / 审核中」会改变全站的可用范围（提示条消失、入口解禁），
 * 而 userInfo 是那些判断的唯一来源 —— 不刷新的话用户回到首页仍看到旧状态。
 * 不在这里跳到下一步：用户可能想先改归属再重填学号，让他自己点「下一步」。
 */
async function onVerified() {
  try {
    await userStore.fetchMe()
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
  if (step.value > 1) {
    // 第 2/3 步允许正常返回上一步
    goto(step.value - 1)
    return
  }
  history.pushState(null, '', location.href)
  ElMessage({ message: '先完成校内认证，或点「下一步」跳过', type: 'info' })
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