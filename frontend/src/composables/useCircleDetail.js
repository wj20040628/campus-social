/**
 * 圈子详情的数据加载与成员操作(2026-10-15 从 CircleDetail.vue 拆出)。
 * ------------------------------------------------------------------
 * 拆出的理由:CircleDetail.vue 351 行超过 300 行门禁。这里收拢的是
 * 「圈子本体 + 两个内容列表(帖子/活动)的加载」与「加入/退出」——
 * 共同点是请求 + 失败语义,和模板渲染无关。页面只留编排
 * (tab、权限计算、写操作 composable 的接线)。
 *
 * 失败语义原样保留(拆的时候一字未动):
 *   - 404 与网络错误分开:前者 notFound,后者 error + 服务端 message
 *   - 内容拉失败保留空列表并由页面 ElMessage 提示(搬进来后提示也在这)
 *   - 加入只本地改 joined 不重拉(不让页面闪一下)
 */
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from '@/utils/feedback'
import {
  detail as detailApi,
  join as joinApi,
  leave as leaveApi,
  circlePosts,
  activitiesByCircle,
} from '@/api/circle'
import { useGuestPrompt } from '@/composables/useGuestPrompt'

export function useCircleDetail(circleId) {
  const { ensureLogin } = useGuestPrompt()
  const circle = ref(null)
  const posts = ref([])
  const activities = ref([])

  const loading = ref(true)
  const error = ref(false)
  /** detail 接口业务错误(422/403)的 message,贴进错误态描述说清原因 */
  const errorMsg = ref('')
  const notFound = ref(false)
  const postsLoading = ref(false)
  const actsLoading = ref(false)
  const joining = ref(false)
  const leaving = ref(false)

  async function load() {
    loading.value = true
    error.value = false
    notFound.value = false
    try {
      circle.value = await detailApi(circleId.value)
      // 圈子存在但接口返回 null:同样按「不存在」处理,不然整个页面永远空着
      if (!circle.value) {
        notFound.value = true
        return
      }
    } catch (e) {
      // 404 与网络错误分开:前者是「圈子没了」,后者是「没加载出来」。
      // 业务错误(422/403)的 message 也带出来,让错误态说清原因,而不是只会「重试」
      if (e?.response?.status === 404) notFound.value = true
      else {
        error.value = true
        errorMsg.value = e?.response?.data?.message || ''
      }
      return
    } finally {
      loading.value = false
    }
    // 圈子加载成功后再拉内容,两个并发
    await Promise.allSettled([loadPosts(), loadActivities()])
  }

  async function loadPosts() {
    postsLoading.value = true
    try {
      const data = await circlePosts(circleId.value, null, 50)
      posts.value = (data?.list || []).map((p) => ({
        ...p,
        /* 保留原始 ISO 时间：展示层 PostCard 用 relTime() 生成相对时间。
           在这里压成 'MM-DD HH:mm' 会让 relTime 解析失败、退回机器格式（2026-10-21 修） */
        createTime: p.createTime,
      }))
    } catch {
      // 内容拉失败保留空列表 + 让空态的出口是「重试」而不是「发第一条」
      posts.value = []
      ElMessage.error('帖子没拉出来,下拉重试')
    } finally {
      postsLoading.value = false
    }
  }

  async function loadActivities() {
    actsLoading.value = true
    try {
      const data = await activitiesByCircle(circleId.value, null, 50)
      activities.value = data?.list || []
    } catch {
      activities.value = []
    } finally {
      actsLoading.value = false
    }
  }

  async function join() {
    // 游客只读:加入要账号,先弹登录提示,不直接发请求吃 401
    if (!(await ensureLogin('登录后才能加入圈子，现在去登录吗？'))) return
    joining.value = true
    try {
      await joinApi(circleId.value)
      // 本地改 joined 而不是重拉整个圈子:一次加入不该让页面闪一下
      circle.value.joined = true
      ElMessage.success('已加入圈子')
    } catch {
      ElMessage.error('没加上,网络好像断了')
    } finally {
      joining.value = false
    }
  }

  /** 退出圈子(仅普通成员 role=3)。确认后物理删成员行 + 成员数 -1。 */
  async function leave() {
    try {
      await ElMessageBox.confirm(
        `确定退出「${circle.value.name}」?退出后不再显示在该圈,可随时再加入。`,
        '退出圈子',
        { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
    leaving.value = true
    try {
      await leaveApi(circleId.value)
      circle.value.joined = false
      circle.value.myRole = 0
      ElMessage.success('已退出圈子')
      await loadPosts() // 退圈后发帖权限没了,发帖框会切到「加入」态
    } catch {
      ElMessage.error('退出失败,再试一次')
    } finally {
      leaving.value = false
    }
  }

  return {
    circle, posts, activities,
    loading, error, errorMsg, notFound, postsLoading, actsLoading, joining, leaving,
    load, loadPosts, loadActivities, join, leave,
  }
}
