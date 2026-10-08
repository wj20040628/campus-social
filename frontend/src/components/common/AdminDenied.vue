<template>
  <!--
    后台页权限不足区块(AdminReview.vue.md §3 / AdminAudit.vue.md §3)
    设计明确要求「与 AdminReview.vue 完全一致(两页共用同一个权限不足区块,
    抽成组件)」—— 两页各写一份,改文案时必然漏掉一处。

    为什么不直接用 StateBlock:StateBlock 是通用态,文案要每次现填;
    而这一屏的文案与图标在设计里是定死的,抽出来顺带把「谁是管理员才看得到」
    这个语义也一起收进组件,调用方只需要传 @action 去哪。
  -->
  <StateBlock
    variant="denied"
    title="这个页面只有管理员能看"
    description="如果你应该是管理员，找负责人开一下权限"
    action-label="回首页"
    :primary="false"
    :icon="Lock"
    @action="$emit('action')"
  />
</template>

<script setup>
import StateBlock from '@/components/common/StateBlock.vue'
import { Lock } from '@element-plus/icons-vue'

defineEmits(['action'])
</script>