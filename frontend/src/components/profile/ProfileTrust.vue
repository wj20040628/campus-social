<template>
  <!--
    个人主页信誉条(2026-10-25)。两种视角,两个信息量:

    他人视角:只渲染一枚「信誉良好」小徽章,且**仅受信时渲染**——
    false 的原因(未认证/新号/前科)后端刻意不下发,前端若给「信誉差」
    就是把前科公开处刑、让新号被误读。不渲染 = 最克制且无歧义的表达。

    本人视角:直接复用设置页的 TrustStatusCard(三条清单+怎么变好),
    同一套文案在两处出现是刻意的一致性,不做第二份。

    加载中/失败整块不渲染:一个加载空壳比没有更糟。
  -->
  <div v-if="showBadge" class="ptrust">
    <span class="ptrust__chip">
      <el-icon class="ptrust__mark" aria-hidden="true"><CircleCheckFilled /></el-icon>
      信誉良好
    </span>
  </div>
  <!-- TrustStatusCard 自己管加载(loaded 才出壳),isMe 直接挂即可 -->
  <TrustStatusCard v-else-if="isMe" class="ptrust__self" />
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { trustBadge } from '@/api/auth'
import TrustStatusCard from '@/components/common/TrustStatusCard.vue'

const props = defineProps({
  targetId: { type: Number, required: true },
  isMe: { type: Boolean, default: false },
})

const trusted = ref(false)

/**
 * 只在「他人」时拉徽章;isMe 走 TrustStatusCard 自取数。
 * 拉失败静默(loaded 不置真)——徽章缺失只是少个信号,不该弹错。
 */
watch(
  () => [props.targetId, props.isMe],
  ([id, isMe]) => {
    trusted.value = false
    if (isMe || !id) return
    trustBadge(id)
      .then((d) => {
        trusted.value = d?.trusted === true
      })
      .catch(() => {
        trusted.value = false
      })
  },
  { immediate: true },
)

const showBadge = computed(() => !props.isMe && trusted.value)
</script>

<style scoped>
.ptrust {
  margin-top: var(--ys-space-2);
}

/* 中性小徽章:用 success 色系但不做大横幅——它是一条佐证,不是一张奖状 */
.ptrust__chip {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-1);
  padding: 3px var(--ys-space-2);
  border-radius: var(--ys-radius-full);
  background: var(--ys-success-soft);
  color: var(--ys-success);
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  font-weight: var(--ys-weight-medium);
}

.ptrust__mark {
  font-size: 14px;
}
</style>
