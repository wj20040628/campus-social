<template>
  <!--
    个人主页信誉条(2026-10-25)。两种视角同一枚徽章,信息量刻意收敛:

    只渲染「信誉良好」绿章,且**仅受信时渲染**——false 的原因(未认证/
    新号/前科)后端刻意不下发,前端若给「信誉差」就是把前科公开处刑、
    让新号被误读。不渲染 = 最克制且无歧义的表达。

    本人视角的章可点:跳「账号与安全」,完整的三条信誉清单在那里
    (TrustStatusCard)——主页保持轻,细节收进设置页。
    他人视角的章是纯展示,没有可去的详情页(清单是隐私)。

    加载中/失败整块不渲染:一个加载空壳比没有更糟。
  -->
  <div v-if="trusted" class="ptrust">
    <button
      v-if="isMe"
      type="button"
      class="ptrust__chip ptrust__chip--link"
      aria-label="查看信誉清单"
      @click="router.push('/settings/security')"
    >
      <el-icon class="ptrust__mark" aria-hidden="true"><CircleCheckFilled /></el-icon>
      信誉良好
      <span class="ptrust__more" aria-hidden="true">›</span>
    </button>
    <span v-else class="ptrust__chip">
      <el-icon class="ptrust__mark" aria-hidden="true"><CircleCheckFilled /></el-icon>
      信誉良好
    </span>
  </div>
</template>

<script setup>
/**
 * 徽章数据:trust-badge 只回一个布尔(本人/他人同一端点同一次查询)。
 * 拉失败静默——徽章缺失只是少个信号,不该在主页弹错。
 */
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { trustBadge } from '@/api/auth'

const props = defineProps({
  targetId: { type: Number, required: true },
  isMe: { type: Boolean, default: false },
})

const router = useRouter()
const trusted = ref(false)

watch(
  () => props.targetId,
  (id) => {
    trusted.value = false
    if (!id) return
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

/* 本人的章可点:保持同一视觉,只加手型与「还有下文」的箭头 */
.ptrust__chip--link {
  border: 0;
  font-family: var(--ys-font-body);
  cursor: pointer;
}

.ptrust__more {
  font-size: var(--ys-text-sm);
  line-height: 1;
}
</style>
