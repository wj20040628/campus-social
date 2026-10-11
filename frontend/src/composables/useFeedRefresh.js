import { ref } from 'vue'

/**
 * Feed 脏标记:内容发生变更(发帖/编辑/删帖)后置 true,
 * Home 在 onActivated 时消费它决定是否重拉第一页。
 *
 * ## 为什么需要它(keep-alive 的代价)
 *
 * Home 被 keep-alive 缓存后,从帖子返回**不会**重新挂载 —— 这正是
 * 「退出帖子回到刚才滚动位置」的前提(feed 数据还在,高度不塌,savedPosition
 * 才恢复得了)。但副作用是:发完帖/改完帖/删完帖回到首页,看到的还是旧列表。
 *
 * 所以用脏标记精确区分两种返回:
 * - 纯浏览返回(看过帖子回来)→ 不刷新,保滚动位置;
 * - 内容变更后返回 → 重拉第一页(此时清空列表跳顶是用户预期内的)。
 */
export const feedDirty = ref(false)

/** 内容变更后调用:标记首页 feed 需要重拉 */
export function markFeedDirty() {
  feedDirty.value = true
}
