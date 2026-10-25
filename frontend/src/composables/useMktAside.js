import { ref, computed } from 'vue'
import * as mkt from '@/api/mkt'

/**
 * 集市详情右栏数据(2026-10-25 从 MktDetail.vue 抽出,该文件贴到 SFC 行数门禁)。
 *
 * 状态文案与颜色：后端下发 status（LAUNCHING/FULL/PAUSED/EXPIRED），
 * 用户据此看出这条是「还能加入」还是「已满」。
 * 「同样在找 N 条」复用 GET /mkt/plaza 按分类查一次，不新增端点；
 * 失败静默置 null（模板 v-if 隐藏整块，它是增强信息）。
 */
export function useMktAside(act, route) {
  const STATUS_TEXT = {
    LAUNCHING: '投放中',
    FULL: '已满员',
    PAUSED: '已暂停',
    EXPIRED: '已过期',
  }

  const statusText = computed(() => STATUS_TEXT[act.value?.status] || '投放中')

  const statusClass = computed(() => {
    const s = act.value?.status
    if (s === 'EXPIRED') return 'panel__v--danger'
    if (s === 'PAUSED' || s === 'FULL') return 'panel__v--warn'
    return 'panel__v--live'
  })

  const sameCatCount = ref(null)

  async function loadSameCat() {
    try {
      const data = await mkt.mktPlaza(act.value?.category || null, null, 20)
      const list = (data?.list || []).filter((a) => String(a.id) !== String(route.params.id))
      sameCatCount.value = list.length
    } catch {
      sameCatCount.value = null
    }
  }

  return { statusText, statusClass, sameCatCount, loadSameCat }
}
