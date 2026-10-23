<template>
  <!--
    群公告单行条(2026-10-20 拆出,2026-10-21 去掉内嵌对话框)。
    改版前公告条整条高亮、占 40px、还带「编辑」二字,比消息本身更显眼;
    而公告一年改两次。现在压成 32px 单行,点开才是全文与编辑入口。

    对话框已经搬到 GroupAnnounceDialog.vue —— 因为 ⋯ 菜单里也有一个「群公告」
    入口,两处必须打开同一个对话框。
  -->
  <button type="button" class="ga" :aria-label="`查看群公告:${announcement}`" @click="emit('open')">
    <svg class="ga__ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M4 9.6h2.4L11 6v12l-4.6-3.6H4a1.1 1.1 0 0 1-1.1-1.1v-2.6A1.1 1.1 0 0 1 4 9.6Z" />
      <path d="M14.4 9.2a4 4 0 0 1 0 5.6" />
    </svg>
    <span class="ga__t">{{ announcement }}</span>
    <svg class="ga__go" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" aria-hidden="true">
      <path d="m9 6 6 6-6 6" />
    </svg>
  </button>
</template>

<script setup>
/**
 * 群公告:只负责那一行 32px 的条。点一下把事件抛给父组件,
 * 由父组件打开 GroupAnnounceDialog —— 本组件不持有任何弹层状态。
 */
defineProps({
  announcement: { type: String, default: '' },
})

const emit = defineEmits(['open'])
</script>

<style scoped src="./GroupAnnounce.css"></style>
