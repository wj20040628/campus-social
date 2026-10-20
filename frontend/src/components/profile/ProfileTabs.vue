<template>
  <!--
    内容分区 Tab 条（2026-10-20 从 Profile.vue 拆出）。
    「带数字」的排版全在这里：数字比标签小一号、淡一档，
    选中项底部一条 26px 的短下划线。他人主页只有一项，父级不渲染本组件。
  -->
  <div class="p-tabbar" role="tablist" aria-label="内容分区">
    <button
      v-for="t in items"
      :key="t.name"
      type="button"
      role="tab"
      class="p-tabbar__item"
      :class="{ 'p-tabbar__item--on': active === t.name }"
      :aria-selected="active === t.name"
      @click="$emit('switch', t.name)"
    >
      {{ t.label }}<span class="p-tabbar__num">{{ t.count }}</span>
    </button>
  </div>
</template>

<script setup>
defineProps({
  /** 按当前视角算好的 Tab 列表：每项含 name / label / count */
  items: { type: Array, default: () => [] },
  /** 当前选中的 Tab name */
  active: { type: String, default: 'posts' },
})

defineEmits(['switch'])
</script>

<style scoped src="./ProfileTabs.css"></style>
