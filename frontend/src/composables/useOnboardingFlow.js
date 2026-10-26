import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as api from '@/api/onboarding'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import { SCHOOL_PROFILE_PAYLOAD } from '@/constants/schoolDict'
import { dictWriteError, refreshDictAfterInvalid, applyDictInvalid } from '@/utils/dictError'

/**
 * 引导页的提交流(Onboarding.vue.md §7)
 * ------------------------------------------------------------------
 * ## 为什么抽出来
 * §7 那条「每一步都调 complete」的规则,连带字典失效分支、字典强制重拉、
 * userInfo 刷新,一共 70 行纯逻辑。把它们和模板挤在一个文件里,
 * Onboarding.vue 就会顶破 300 行门禁 —— 而门禁一破,人只会想办法放宽门禁。
 *
 * ## 三条不能省的语义
 * 1. **字典失效(1040/1041)不跳步**。跳了就把「下拉选错了一个值」
 *    变成「数据丢了要重填」,代价差了两个数量级。
 * 2. **字典失效后强制重拉**。用户手里那份列表**仍然含**已停用的项,
 *    只提示不刷新 = 用户会反复选中同一个无效项,进死循环。
 * 3. **§7:每一步都调 complete**,前端不记「我调过没有」——
 *    记了就一定会有漏掉的分支。
 *
 * @param {object} deps
 * @param {import('vue').Ref} deps.step        当前步(1=兴趣标签, 2=关注/圈子)
 * @param {import('vue').Ref} deps.identity    学校归属 { schoolId, deptId, gradeId }(从 userInfo 预填,不再由引导收集)
 * @param {import('vue').Ref} deps.tagIds      第 1 步已选标签
 * @param {import('vue').Ref} deps.userIds     第 2 步已关注用户
 * @param {import('vue').Ref} deps.circleIds   第 2 步已加入圈子
 * @param {() => void} deps.goto          切步(会清 submitError)
 * @param {() => void} deps.loadTags      进第 2 步时拉标签
 * @param {() => void} deps.loadRecommend 进第 3 步时拉推荐
 * @param {() => void} deps.clearTags     「以后再选」时清空标签
 * @param {() => void} deps.clearFollows  「先逛逛」时清空关注
 * @param {import('vue').Ref} deps.canAdvance  当前步能否提交(由主组件按步算)
 */
export function useOnboardingFlow(deps) {
  const router = useRouter()
  const userStore = useUserStore()
  const dict = useDictStore()

  const submitting = ref(false)
  const submitError = ref('')
  /** 后端判定的无效字段,透传给第 1 步的 SchoolFields 做字段级提示 */
  const identityInvalid = ref({})

  /**
   * 主按钮文案。
   *
   * 2026-10-21 步数从 2 改 3（新增第 1 步认证），所以「最后一步」从 2 变成 3。
   * 最后一步的文案是「进友声」而不是「完成」：这一步的语义是
   * 「你可以开始用了」，不是「你提交了一份表单」（§1.3）。
   */
  const primaryLabel = computed(() => (deps.step.value === 3 ? '进友声' : '下一步'))

  const primaryEnabled = computed(() => {
    if (submitting.value) return false
    return !!deps.canAdvance?.value
  })

  /** §7 的核心:每一步都调,后端幂等覆盖 */
  async function persist() {
    await api.complete({
      [SCHOOL_PROFILE_PAYLOAD.schoolId]: deps.identity.value.schoolId,
      campus: deps.identity.value.campus ?? null,
      [SCHOOL_PROFILE_PAYLOAD.deptId]: deps.identity.value.deptId,
      [SCHOOL_PROFILE_PAYLOAD.gradeId]: deps.identity.value.gradeId,
      [SCHOOL_PROFILE_PAYLOAD.majorId]: deps.identity.value.majorId,
      tagIds: deps.tagIds.value,
      followUserIds: deps.userIds.value,
      circleIds: deps.circleIds.value,
    }, { silent: true })
  }

  /**
   * 判定这次失败是不是「学院/年级已失效」。
   * @returns {Promise<boolean>} true = 已按字典失效处理,调用方不要再走通用失败分支
   */
  async function handleDictInvalid(e) {
    const bad = dictWriteError(e)
    if (!bad) return false
    identityInvalid.value = applyDictInvalid(deps.identity.value, bad)
    await refreshDictAfterInvalid(dict)
    return true
  }

  /**
   * 提交失败的吸底栏文案,按错误类型分流(§5 A1):
   * - 业务错(拦截器 reject 的 new Error,带 isBusiness):用后端 message 原文
   * - 网络错(无 response:连接断/超时):「网络不太好,再点一次」
   * - 其它 HTTP 错:用后端 message 兜底
   * 字典失效(1040/1041)已在调用方先走 handleDictInvalid,不进这里。
   */
  function failSubmit(e) {
    if (e?.isBusiness) {
      submitError.value = e.message || '提交失败,再点一次'
    } else if (!e?.response) {
      submitError.value = '网络不太好,再点一次'
    } else {
      submitError.value = e?.response?.data?.message || '提交失败,再点一次'
    }
  }

  /** 进首页。必须先刷 userInfo,否则守卫会按旧的 profileComplete=false 弹回来 */
  async function enter() {
    try {
      await userStore.fetchMe()
    } catch {
      /* 静默:localStorage 里的旧 userInfo 只让守卫多判一次,不至于卡死 */
    }
    router.replace('/')
  }

  async function onPrimary() {
    if (!primaryEnabled.value) return
    submitting.value = true
    submitError.value = ''
    identityInvalid.value = {} // 清掉上一次的失效判定,否则提示会一直挂着
    try {
      await persist()
      /*
       * 三步步进（2026-10-21 从两步改三步）：
       *   1 学号认证 → 2 兴趣标签 → 3 关注/圈子 → 进首页
       *
       * 第 1 步的认证本身由 StepVerify 组件内部完成（它直接调 /verify/student-no），
       * 这里 persist() 只是把归属一并落到 onboarding 的口径里，然后推进。
       */
      if (deps.step.value === 1) {
        // 第 1 步（认证）提交后进第 2 步，需要先有标签数据
        deps.goto(2)
        deps.loadTags()
      } else if (deps.step.value === 2) {
        // 第 2 步（兴趣标签）提交后进第 3 步，拉推荐同学/圈子
        deps.goto(3)
        deps.loadRecommend()
      } else {
        // 第 3 步（关注/圈子）：完成并进首页
        await enter()
      }
    } catch (e) {
      if (await handleDictInvalid(e)) return
      // §3 错误态:**已选内容全部保留** —— 引导页丢数据是最伤的
      failSubmit(e)
    } finally {
      submitting.value = false
    }
  }

  /** 「先逛逛」:跳过关注/圈子,直接完成并进首页,不是退出引导(§1.4) */
  async function onSkip() {
    deps.clearFollows()
    submitting.value = true
    submitError.value = ''
    try {
      await persist()
      await enter()
    } catch (e) {
      if (await handleDictInvalid(e)) return
      failSubmit(e)
    } finally {
      submitting.value = false
    }
  }

  return {
    submitting, submitError, identityInvalid,
    primaryLabel, primaryEnabled,
    onPrimary, onSkip,
  }
}