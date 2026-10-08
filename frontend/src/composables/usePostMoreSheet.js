import { ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { blacklist } from '@/api/relation'

/**
 * 帖子详情「更多」操作表的行为(PostDetail.vue.md §1 行 1)
 * ------------------------------------------------------------------
 * 抽出来的原因不是复用(目前只有 PostDetail 用),而是**内聚**:
 * 三个入口共享同一组「先关表、再干活」的开关,散在页面里时
 * 每加一个入口就容易漏掉关表那一步,结果表叠着表不消失。
 *
 * 三项对应设计的 ActionSheet:举报 / 复制链接 / 不看此作者。
 *
 * @param {{router: import('vue-router').Router, authorId: import('vue').Ref<number|null>}} deps
 */
export function usePostMoreSheet({ router, authorId }) {
  const moreOpen = ref(false)
  const reportOpen = ref(false)
  const verifyOpen = ref(false)

  /** 三个入口共用:先把表关掉再干活 */
  function closeThen(fn) {
    moreOpen.value = false
    return fn()
  }

  function openReport() {
    closeThen(() => {
      reportOpen.value = true
    })
  }

  async function copyLink() {
    closeThen(async () => {
      try {
        await navigator.clipboard.writeText(window.location.href)
        ElMessage.success('链接已复制')
      } catch {
        // 非安全上下文(iOS http、微信内置浏览器)没有 clipboard API
        ElMessage.warning('这个浏览器不让复制，长按地址栏试试')
      }
    })
  }

  /**
   * 不看这个作者。
   *
   * 后端没有独立的「不看」端点,能落库的是拉黑 —— 而拉黑会连带**双向取关**
   * (RelationServiceImpl.blacklist 里调了两次 unfollow),比「不想在广场
   * 看到这个人」重得多。所以成功文案里把代价明说出来:
   * 用一个轻的词描述一个重的动作,用户事后发现被取消关注,会觉得被骗。
   */
  async function hideAuthor() {
    closeThen(async () => {
      const uid = authorId.value
      if (!uid) return
      try {
        await blacklist(uid)
        ElMessage.success('已拉黑并取消关注，之后看不到他的动态')
        router.push('/')
      } catch {
        /* request.js 已弹错,留在原地,表已关 */
      }
    })
  }

  /** AC-11:未认证点「搭句话」时由 GreetButton 抛来,本页负责说明与出口 */
  function openVerify() {
    closeThen(() => {
      verifyOpen.value = true
    })
  }

  function goVerify() {
    verifyOpen.value = false
    router.push('/onboarding')
  }

  return { moreOpen, reportOpen, verifyOpen, openReport, copyLink, hideAuthor, openVerify, goVerify }
}