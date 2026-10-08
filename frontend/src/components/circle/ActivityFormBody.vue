<template>
  <!--
    活动表单体(手机抽屉与桌面弹窗共用)
    拆出来是为了「字段与校验只有一份」——两个外壳的 props 差异大到
    用 v-if 切会更乱,但字段一旦各写一遍,手机端就会漏掉 label-position="top"。
  -->
  <el-form
    :model="form"
    :label-position="labelPosition"
    :label-width="labelPosition === 'left' ? labelWidth : undefined"
    class="fb"
  >
    <el-form-item label="标题">
      <el-input v-model="form.title" class="fb__ctl" maxlength="128" placeholder="一句话说清是什么活动" />
    </el-form-item>

    <el-form-item label="名额">
      <el-input-number v-model="form.capacity" :min="0" :max="99999" class="fb__ctl" />
      <span class="fb__hint">填 0 就是不限人数</span>
    </el-form-item>

    <!--
      两个 datetime picker **各占满一行**(CircleDetail.vue.md §7 第 2 点):
      EP 的 datetime 弹出面板在 375px 下约 320px 宽,并排时第二个会被裁切。
      桌面端同样各占一行 —— 并排在桌面也没有好处。
    -->
    <el-form-item label="开始时间">
      <el-date-picker
        v-model="form.startTime"
        type="datetime"
        class="fb__ctl"
        placeholder="选择开始时间"
      />
    </el-form-item>

    <el-form-item label="结束时间">
      <el-date-picker
        v-model="form.endTime"
        type="datetime"
        class="fb__ctl"
        placeholder="选择结束时间"
      />
    </el-form-item>

    <p v-if="error" class="fb__err" role="alert">{{ error }}</p>
  </el-form>
</template>

<script setup>
defineProps({
  form: { type: Object, required: true },
  error: { type: String, default: '' },
  /** 'top' = 手机（红线 3：label-position="top"）; 'left' = 桌面 */
  labelPosition: { type: String, default: 'top' },
  labelWidth: { type: String, default: '' },
})
</script>

<style scoped>
.fb {
  display: flex;
  flex-direction: column;
}

.fb__ctl {
  width: 100%;
}

.fb__hint {
  margin-left: var(--ys-space-3);
  font-size: var(--ys-text-xs);
  color: var(--ys-meta);
}

.fb__err {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-danger);
}

/* label 13px/500/--ys-fg-2(§1.3) */
.fb :deep(.el-form-item__label) {
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}
</style>
