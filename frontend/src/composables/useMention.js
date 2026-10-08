import { ref, nextTick } from 'vue'
import { mentionCandidates } from '@/api/auth'
import { following as followingApi, followers as followersApi } from '@/api/relation'

/**
 * 评论框的 @ 提及(快手式:点按钮 = 推荐人,输 @ = 关键字搜)。
 *
 * 从 PostDetail.vue 抽出:这是一套完整的状态机(防竞态 seq + 推荐人缓存 +
 * 光标位置计算),和帖子读取/点赞/删除没有共享状态,内联只会把文件推过 300 行。
 *
 * 关键设计:
 * - seq 自增防竞态:旧搜索响应不覆盖新输入(打字很快时网络乱序)
 * - 推荐人只拉一次并缓存:连点两次 @ 不该打两次接口
 */
export function useMention(inputRef, myId, content) {
  const mentionOpen = ref(false)
  const mentionList = ref([])
  const mentionKw = ref('')   // 当前搜索关键字(空 = 推荐模式)
  const mentionIds = ref([]) // 已选被 @ 用户 id(唯一,提交时带上)

  let seq = 0             // 防竞态序号
  let loaded = false      // 推荐人是否已拉过
  const cache = []        // 推荐人缓存(敲 @ 空字时复用)

  /** 推荐人:我关注的 + 粉丝(去重,关注优先) */
  async function loadRecommended() {
    const me = myId.value
    if (!me) return
    try {
      const [f, s] = await Promise.all([
        followingApi(me, null, 30),
        followersApi(me, null, 30),
      ])
      const seen = new Set()
      const merged = [...(f?.list || []), ...(s?.list || [])]
        .filter(Boolean)
        .filter((u) => {
          if (seen.has(u.id)) return false
          seen.add(u.id)
          return true
        })
        .slice(0, 24)
      mentionList.value = merged
      cache.length = 0
      cache.push(...merged)
      mentionKw.value = ''
      loaded = true
    } catch {
      mentionList.value = []
    }
  }

  /** 光标前最近的 @ 后到光标的内容 = 关键字;没有 @ 或 @ 后面跨行了就关浮层 */
  function onInput() {
    const el = inputRef.value?.textarea
    const pos = el ? el.selectionStart : content.value.length
    const before = content.value.slice(0, pos)
    const at = before.lastIndexOf('@')
    if (at === -1 || before.slice(at + 1).includes('\n')) {
      mentionOpen.value = false
      return
    }
    const kw = before.slice(at + 1)
    mentionKw.value = kw
    mentionOpen.value = true

    if (kw === '') {
      // 刚敲 @ 还没输字:弹推荐人(我关注 + 粉丝),不查全库
      if (!loaded) loadRecommended()
      else mentionList.value = cache.slice()
      return
    }

    // 键入了实际字符:走关键字搜索
    const mine = ++seq
    mentionCandidates(kw)
      .then((list) => {
        // 响应回来时序号已被新输入覆盖 → 丢弃,否则会显示过期结果
        if (mine !== seq) return
        mentionList.value = Array.isArray(list) ? list : []
      })
      .catch(() => {
        mentionList.value = []
      })
  }

  /** @ 按钮:关 → 开推荐面板;开 → 关(再点一次收起) */
  function toggle() {
    if (mentionOpen.value) {
      mentionOpen.value = false
      return
    }
    mentionOpen.value = true
    if (!loaded) {
      loadRecommended()
    } else {
      mentionKw.value = ''
      inputRef.value?.textarea?.focus()
    }
  }

  function pick(u) {
    const el = inputRef.value?.textarea
    const pos = el ? el.selectionStart : content.value.length
    const before = content.value.slice(0, pos)
    const after = content.value.slice(pos)
    const at = before.lastIndexOf('@')

    // 两种进入方式:① 输入 @ 后关键字搜 → 替换掉那个 @;② 点 @ 按钮弹推荐 → 光标处直接插
    const isFreshAt = at !== -1 && before.slice(at + 1) === ''
    const head = isFreshAt ? before.slice(0, at) : before
    const insert = '@' + (u.nickname || u.username) + ' '

    content.value = head + insert + after
    if (!mentionIds.value.includes(u.id)) mentionIds.value.push(u.id)
    mentionOpen.value = false
    mentionKw.value = ''

    nextTick(() => {
      // 光标落到插入内容之后,而不是留在末尾(否则 @ 后面再打字会跑到后头)
      const np = head.length + insert.length
      if (!el) return
      el.focus()
      el.setSelectionRange(np, np)
    })
  }

  /** 失焦略延迟关浮层,让候选项的 @mousedown 先触发 */
  function onBlur() {
    setTimeout(() => { mentionOpen.value = false }, 150)
  }

  function reset() {
    content.value = ''
    mentionIds.value = []
    mentionOpen.value = false
    mentionKw.value = ''
  }

  return {
    mentionOpen, mentionList, mentionKw, mentionIds,
    onInput, toggle, pick, onBlur, reset,
  }
}