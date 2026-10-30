/**
 * 校园信息核对队列(AdminVerify.vue)。
 *
 * [为什么抽出来]
 * 与 useReviewQueue 同构:游标分页 + 无限滚动 + 两个处置动作 + 乐观出栈。
 * 留在视图里会让它同时关心布局、分页、滚动观察、失败回滚 ——
 * 单文件 300 行门禁下必然要砍掉注释,而本项目吃过「注释被砍后
 * 那条坑被人再踩一遍」的亏。
 *
 * [与机审队列的三处实质差异,不要照抄 useReviewQueue]
 *   1. 没有撤销窗口。契约里**没有**撤销端点(只用 message 回包),
 *      approve/reject 是终态动作。硬做一个假的 5 秒撤销条 =
 *      管理员点了撤销、后端没回退、界面却显示已撤销 —— 沉默的逻辑错误。
 *   2. 404 是正常结果,不是错误。两个管理员同时点同一条时,后一个必然拿到
 *      404 VERIFY_NOT_FOUND(1044)。处理办法是「保持已移除 + 提示已被处理」,
 *      不是报错重试。
 *   3. 驳回理由走「点开再填」,不是常驻输入框(见 openReject)。
 */
import { onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { approveVerify, pendingVerify, rejectVerify, } from '@/api/review'
import { useAdminPending } from '@/composables/useAdminPending'

/** 单页条数。20 条在 375px 下是 4~5 屏 */
const PAGE = 20

/**
 * 404 VERIFY_NOT_FOUND 的判定。
 *
 * 只看 status 不看 code:契约把这两个端点的 404 **唯一**绑定给
 * VERIFY_NOT_FOUND,而 request.js 抛的是 axios error,code 在
 * `error.response.data.code` 里,要穿过两层可选链;status 是一层的。
 * 将来若该端点新增别的 404 语义,这里必须改。
 */
function alreadyHandled(err) {
  return err?.response?.status === 404
}

export function useVerifyQueue() {
  const list = ref([])
  const cursor = ref(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const failed = ref(false)
  /** 提交中的 userId。用对象而不是 Set:模板里 `busy[item.userId]` 要可读 */
  const busy = reactive({})
  /** 正在展开驳回理由输入框的 userId;null 表示没有 */
  const rejecting = ref(null)
  const reason = ref('')

  let observer = null

  /** @param {boolean} reset true = 回到第一页,false = 追加下一页 */
  async function load(reset = false) {
    if (loading.value) return
    if (reset) {
      cursor.value = null
      hasMore.value = true
      failed.value = false
      list.value = []
    }
    loading.value = true
    try {
      const data = await pendingVerify(cursor.value, PAGE)
      const rows = Array.isArray(data?.list) ? data.list : []
      list.value = reset ? rows : [...list.value, ...rows]
      hasMore.value = data?.hasMore === true
      // hasMore 为 false 时游标必须清空:留着旧游标会让下一次(不可能发生的)
      // 追加请求拿到重复页。null 更安全。
      cursor.value = hasMore.value ? (data?.nextCursor ?? null) : null
    } catch {
      // request.js 已弹红条,这里不重复提示。
      // 列表不清空:已经拉到的 20 条比一个空白页有用,管理员还能手动重试。
      failed.value = true
    } finally {
      loading.value = false
    }
  }

  /**
   * 无限滚动哨兵。
   *
   * 两条踩过的坑(与 useReviewQueue 同源):
   *   - 哨兵必须在滚动容器**外面**,否则列表为空时它一开始就在视口里,
   *     IntersectionObserver 连发多次 load,第一页被重复追加。
   *   - 必须等 DOM 更新再 querySelector,否则 .verify-admin__sentinel 还不存在。
   */
  function observeSentinel() {
    observer?.disconnect()
    requestAnimationFrame(() => {
      const el = document.querySelector('.verify-admin__sentinel')
      if (!el) return
      observer = new IntersectionObserver(
        (entries) => {
          if (entries[0]?.isIntersecting && !loading.value && hasMore.value) load()
        },
        { threshold: 0.1, rootMargin: '200px' },
      )
      observer.observe(el)
    })
  }

  /**
   * 乐观出栈,并留一张「放回原位」的快照。
   *
   * 为什么要记 index:失败时把条目塞回列表顶部,管理员会看到它跳了位置,
   * 以为刚才那一下没生效于是再点一次 —— 两次提交同一条,第二次必然 404。
   * 塞回原索引才不会诱导这个误判。
   */
  function drop(userId) {
    const index = list.value.findIndex((x) => x.userId === userId)
    if (index < 0) return null
    const item = list.value[index]
    list.value.splice(index, 1)
    return { item, index }
  }

/**
 * 处置失败的统一出口。
 *
 * **不在这里弹错误 toast** —— request.js 的响应拦截器已经弹过一条
 * (`error.response.data.message`),再弹一条会出现**两条一模一样的红条**,
 * 看起来像页面坏了两次。
 *
 * 失败后的反馈由「卡片回到原位」本身承担:管理员刚点的那张卡就在眼前,
 * 它消失又出现,比多一条 toast 更直接(实测 500 时卡片确实回到了点击时的位置)。
 * 这与 useReviewQueue 的 load() catch 里「request.js 已弹错,这里不重复提示」
 * 是同一个口径。
 */
function putBack(snap) {
  if (!snap) return
  const at = Math.min(snap.index, list.value.length)
  list.value.splice(at, 0, snap.item)
}

  async function approve(item) {
    const id = item.userId
    if (busy[id]) return
    const snap = drop(id)
    busy[id] = true
    try {
      await approveVerify(id)
      ElMessage.success('已标记为已登记（只更新资料标记，不影响功能权限）')
      useAdminPending().refresh(true) // 队列数变了,抽屉/侧栏角标立刻跟上
    } catch (err) {
      // 已被处理:保持移除状态,不塞回去,也不报红
      if (alreadyHandled(err)) {
        ElMessage.info('该申请已被处理')
        return
      }
      putBack(snap)
    } finally {
      busy[id] = false
    }
  }

  /**
   * 展开某条的驳回输入框。
   *
   * 为什么不是常驻输入框:机审队列(AdminReview)里理由框常驻,是因为那一屏
   * 只处置 1~2 条;这里一屏 20 条,常驻会让每张卡片高出一倍,
   * 管理员要滚很久才看得到下一条。
   *
   * 2026-10-28：打开时**预填自动生成的建议理由**。此前每条都要管理员
   * 手打一遍「学校信息和学号不匹配」，20 条就是 20 遍。生成规则只读后端
   * 已经算好的三个比对位，不在这里猜学号。
   */
  function openReject(item) {
    rejecting.value = item.userId
    reason.value = suggestReason(item)
  }

  /**
   * 按比对结果拼建议理由。三项都不符时合成一句，避免「学院不符、专业不符、
   * 年级不符」三条独立句读起来像三次不同的错误。
   * 全为 true/null 时返回空串——没有依据就不替管理员编理由。
   */
  function suggestReason(item) {
    const bad = []
    if (item?.deptMatch === false) bad.push('学院')
    if (item?.majorMatch === false) bad.push('专业')
    if (item?.gradeMatch === false) bad.push('年级')
    if (!bad.length) return ''
    return `${bad.join('、')}与学号不匹配，请核对后重新提交`
  }

  function cancelReject() {
    rejecting.value = null
    reason.value = ''
  }

  async function confirmReject(item) {
    const id = item.userId
    const text = reason.value.trim()
    if (!text) {
      // 后端 @Valid 也会拦,但那要等一个来回;本地先拦,管理员不用等
      ElMessage.warning('驳回要写理由，用户要靠它知道改哪里')
      return
    }
    if (busy[id]) return
    const snap = drop(id)
    rejecting.value = null
    reason.value = ''
    busy[id] = true
    try {
      await rejectVerify(id, text)
      ElMessage.success('已驳回，理由会通知该用户')
      useAdminPending().refresh(true)
    } catch (err) {
      if (alreadyHandled(err)) {
        ElMessage.info('该申请已被处理')
        return
      }
      putBack(snap)
    } finally {
      busy[id] = false
    }
  }

  onBeforeUnmount(() => {
    observer?.disconnect()
  })

  return {
    list,
    hasMore,
    loading,
    failed,
    busy,
    rejecting,
    reason,
    load,
    observeSentinel,
    approve,
    openReject,
    cancelReject,
    confirmReject,
  }
}
