import { ref, computed } from 'vue'
import dayjs from 'dayjs'
import { me as meApi, userCard } from '@/api/auth'
import { myPosts as myPostsApi } from '@/api/feed'
import { mine as myReviewsApi } from '@/api/review'
import { readIdentity } from '@/api/dict'

/**
 * 个人主页的资料加载(Profile.vue.md §3)
 * ------------------------------------------------------------------
 * 三条独立的失败语义,混在一起会写错:
 *   1. 资料拉不到(用户注销 / 链接错)→ **整页替换**「这个用户不见了」
 *   2. 动态列表拉不到 → 只在 Tab 内容区报错,主页身份头仍可用
 *   3. 审核记录拉不到 → 静默。审核记录是加分项,拉不到不该让用户看到报错
 *
 * 原实现 `load()` 没有 try/catch,资料失败时 user 保持 null,
 * 模板的 `v-if="!user"` 会永远命中骨架屏 —— 用户看到的是一个
 * 永远转圈的页面,连「出错了」都不知道。
 */
export function useProfileData(targetId, myId, dict) {
  const user = ref(null)
  const posts = ref([])
  const myReviews = ref([])
  /** 资料级失败(对应态 1) */
  const loadFailed = ref(false)
  /** 动态列表失败(对应态 2)。身份头仍可用,只有 Tab 内容区替换 */
  const postsFailed = ref(false)

  function normalize(list) {
    return (list || []).map((p) => ({
      ...p,
      createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
    }))
  }

  async function load() {
    loadFailed.value = false
    try {
      if (myId.value === targetId.value) {
        // 自己:/user/me 是全字段,顺带把动态与审核记录一次拉齐
        user.value = await meApi()
        await loadPosts()
        loadMyReviews()
      } else {
        /**
         * 他人:只有卡片。后端 `GET /post/mine` 取的是**登录人**(AuthInterceptor.ATTR_USER_ID),
         * 没有任何「某用户发的帖子」端点 —— 所以不能传 targetId 去查,
         * 那样请求会带着登录人的身份发出去,返回的是**我的**动态,
         * 挂在别人的主页上比空列表更糟(张冠李戴,用户看不出错在哪)。
         * 结果就是:他人主页的「动态」这一格显示 0,点开是空态。
         * 这条缺口已在交付报告里列给后端(需要 GET /user/{id}/posts)。
         */
        user.value = await userCard(targetId.value)
        posts.value = []
        myReviews.value = []
      }
    } catch {
      loadFailed.value = true
    }
  }

  /**
   * 动态列表。**不抛** —— 失败只置 postsFailed,
   * 让身份头继续显示,否则一次列表请求失败会把整个主页变成「用户不见了」。
   */
  async function loadPosts() {
    postsFailed.value = false
    try {
      const data = await myPostsApi(null, 50)
      posts.value = normalize(data?.list)
    } catch {
      postsFailed.value = true
    }
  }

  /** 审核记录拉不到就当没有(设计 §1.3:它是补充信息,不是主路径) */
  async function loadMyReviews() {
    try {
      const data = await myReviewsApi(null, 50)
      myReviews.value = (data?.list || []).filter(Boolean)
    } catch {
      myReviews.value = []
    }
  }

  /** 切人(路由复用)时全清并重载 */
  function reset() {
    user.value = null
    posts.value = []
    myReviews.value = []
    loadFailed.value = false
    postsFailed.value = false
  }

  /**
   * 学院 · 年级展示。三级兜底(后端 name → 按 id 反查字典 → 旧自由文本)。
   * 字典要先到位,否则存量用户会先闪一下空再跳成有值。
   */
  const identity = computed(() =>
    readIdentity(user.value, { depts: dict.depts, grades: dict.grades }),
  )

  /** 「计算机学院 · 2024 级」。缺哪项省哪项,不留下多余的「· 」 */
  const identityLine = computed(() =>
    [identity.value.deptName, identity.value.gradeName].filter(Boolean).join(' · '),
  )

  /**
   * AC-12 徽章判据。**直接用后端下发的 user.verified**(按 verify_status 计算,
   * /user/me 与 /user/{id} 均正确填充)。
   *
   * 早前的漂移史:徽章写 user.verified 恒假(后端当时没填),改成
   * 「有 dept/grade 名就算认证」后徽章亮了 —— 但那会把字典化之前填过
   * 自由文本院系的老用户误判成已认证(WJ 实测:verify_status=0 却亮徽章,
   * 发私信被护栏拦下,用户以为「这号已经认证了」)。后端字段补齐后判据归位。
   */
  const verified = computed(() => user.value?.verified === true)

  return {
    user, posts, myReviews, loadFailed, postsFailed,
    load, loadPosts, loadMyReviews, reset,
    identity, identityLine, verified,
  }
}