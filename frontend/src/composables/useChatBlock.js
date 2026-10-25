/**
 * 会话输入阻断横幅的展示态(2026-10-24 从 ChatThread 抽出,缓解其 400 行门禁)。
 * block 数据由 Chat.vue 计算(群禁言/未认证/额度三态),这里只做展示字段派生。
 */
import { computed } from 'vue'

export function useChatBlock(props) {
  const blockReason = computed(() => props.block?.text || '')
  const blockTone = computed(() => props.block?.tone || 'danger')
  return { blockReason, blockTone }
}
