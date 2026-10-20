import { ref } from 'vue'
import dayjs from 'dayjs'
import { favoriteList, likedList } from '@/api/feed'

/**
 * 个人主页的「收藏」「赞过」两个 Tab 的数据。
 *
 * 为什么不并进 useProfileData:那三个失败语义(资料/动态/审核)已经够复杂,
 * 再加两个"懒加载 + 各自失败"的列表,会让「哪条失败该整页替换、哪条不该」
 * 变得读不出来。这里单独一个 composable,失败只在对应 Tab 内报错。
 *
 * 为什么懒加载:收藏和赞过是次要内容,进主页就并发拉两个列表
 * 会让首屏多两次往返,而大部分用户进来是看自己的动态。
 */
export function useProfileCollections(isMe) {
  const favorites = ref([])
  const liked = ref([])
  const favLoading = ref(false)
  const likedLoading = ref(false)
  const favFailed = ref(false)
  const likedFailed = ref(false)
  /** 已加载过就不再重复请求:切 Tab 来回点不该每次都打接口 */
  const favLoaded = ref(false)
  const likedLoaded = ref(false)

  function normalize(list) {
    return (list || []).map((p) => ({
      ...p,
      createTime: dayjs(p.createTime).format('MM-DD HH:mm'),
    }))
  }

  /**
   * 收藏列表。游标由后端返回,这里只取第一页(size 50 足够覆盖个人主页这种场景;
   * 真到需要无限滚动的量级,再补 onReachBottom 接线)。
   */
  async function loadFavorites() {
    if (!isMe.value || favLoaded.value || favLoading.value) return
    favFailed.value = false
    favLoading.value = true
    try {
      const data = await favoriteList(null, 50)
      favorites.value = normalize(data?.list)
      favLoaded.value = true
    } catch {
      // 失败不标记 loaded,用户切回来会重试
      favFailed.value = true
    } finally {
      favLoading.value = false
    }
  }

  /** 赞过列表,语义同 loadFavorites */
  async function loadLiked() {
    if (!isMe.value || likedLoaded.value || likedLoading.value) return
    likedFailed.value = false
    likedLoading.value = true
    try {
      const data = await likedList(null, 50)
      liked.value = normalize(data?.list)
      likedLoaded.value = true
    } catch {
      likedFailed.value = true
    } finally {
      likedLoading.value = false
    }
  }

  /** 切到别人主页时清空:收藏/赞过是私人数据,不能残留上一个用户的 */
  function reset() {
    favorites.value = []
    liked.value = []
    favLoaded.value = false
    likedLoaded.value = false
    favFailed.value = false
    likedFailed.value = false
  }

  return {
    favorites, liked,
    favLoading, likedLoading,
    favFailed, likedFailed,
    loadFavorites, loadLiked, reset,
  }
}
