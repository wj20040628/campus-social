<template>
  <!--
    集市活动详情 · 安全须知弹窗（2026-10-21 新增）。

    【为什么从常驻改成弹窗】
    改版前免责声明在详情页出现两次（信息区下方一大段 + 留言区上方一条橙色警示），
    每次占 4 行以上，把正文和留言区隔开。这类「你已知晓风险」的告知，
    用户真正需要的是**知悉一次**，而不是每屏都重复。

    【「不再提醒」的生效范围】本次登录内有效，用 sessionStorage 存。
    这正是用户要求的口径：「这次登录不再提醒，退出登录后下次再登录还会提醒」。

    【为什么自己写遮罩而不用 el-dialog】
    试过 el-dialog + v-model，但它的内部 open 状态与我们的 props 双向同步时
    会互相回写（父 props 变 → 内部 open 变 → 又 emit 回去），
    实测点「我知道了」后弹窗关不掉。这里只有一个开关、两个按钮、无动画需求，
    自己写 20 行遮罩反而更可控：v-if 控制挂载，不依赖任何第三方内部状态。
  -->
  <Teleport to="body">
    <div v-if="modelValue" class="risk__mask" @click.self="onMaskClick">
      <div class="risk__dialog" role="dialog" aria-modal="true" aria-labelledby="risk-title">
        <h3 id="risk-title" class="risk__t">
          <el-icon :size="18"><WarningFilled /></el-icon>
          安全须知
        </h3>

        <p class="risk__body">
          本平台仅提供信息展示与线上沟通渠道，<b>不参与任何线下交易</b>。<br />
          见面请在<b>校内公共区域</b>，涉及金钱请当面结清，<b>拒绝私下转账</b>。
        </p>

        <label class="risk__check">
          <input v-model="dontRemind" type="checkbox" class="risk__input" />
          <span class="risk__box" :class="{ 'risk__box--on': dontRemind }" aria-hidden="true" />
          <span class="risk__label">本次登录不再提醒</span>
        </label>

        <button type="button" class="risk__ok" @click="confirm">我知道了</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'

const props = defineProps({
  /** 是否显示（由父组件控制） */
  modelValue: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'ack'])

/** 勾选「本次登录不再提醒」。只在本次弹窗生命周期内有效 */
const dontRemind = ref(false)

/** 点遮罩不关闭 —— 安全告知需要用户明确点「我知道了」 */
function onMaskClick() {
  /* 刻意留空：不给「点外面就关掉」的退路，避免误关后以为已知悉 */
}

function confirm() {
  emit('ack', { dontRemind: dontRemind.value })
  emit('update:modelValue', false)
  dontRemind.value = false
}
</script>

<style scoped src="./MktRiskNotice.css"></style>
