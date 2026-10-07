import { ref, onUnmounted } from 'vue'

/**
 * 无限滚动:用 IntersectionObserver 观察哨兵元素
 * @param {() => Promise<boolean>} loadMore - 返回 hasMore
 * @param {() => void} onReset - 重置时调用
 * @returns {{ loadMore: () => void, reset: () => void, sentinelEl: Ref, isIntersecting: Ref }}
 */
export function useInfiniteScroll(loadMore, onReset) {
  const sentinelEl = ref(null)
  const observer = ref(null)
  const loading = ref(false)
  const done = ref(false)
  const sortKey = ref(null) // 切 tab 重置

  function setup() {
    if (!sentinelEl.value) return
    teardown()
    observer.value = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting && !loading.value && !done.value) {
          trigger()
        }
      },
      { threshold: 0.1, rootMargin: '200px' }
    )
    observer.value.observe(sentinelEl.value)
  }

  async function trigger() {
    if (loading.value || done.value) return
    loading.value = true
    try {
      const hasMore = await loadMore()
      if (!hasMore) done.value = true
    } finally {
      loading.value = false
      setup() // 重新观察(切 tab/重置后)
    }
  }

  function reset() {
    done.value = false
    loading.value = false
    onReset?.()
    setup()
  }

  /** 切排序 tab:重置 cursor + 列表 */
  function switchSort(newKey) {
    sortKey.value = newKey
    reset()
  }

  function teardown() {
    observer.value?.disconnect()
    observer.value = null
  }

  onUnmounted(teardown)

  return {
    sentinelEl,
    setup,
    trigger,
    reset,
    switchSort,
    get isDone() {
      return done.value
    },
  }
}
