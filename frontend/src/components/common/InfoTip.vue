<template>
  <!--
    通用信息提示条(2026-10-24):重要页面给用户的一句"在这里该怎么用/会发生什么"。

    [与 toast/弹窗的分工]
    toast 说"刚发生的这件事的结果";本条说"这个页面的规则"——
    属于用户第一次需要知道、之后不该反复打扰的知识,所以可关闭且记住。

    [与 VerifyNotice 的分工]
    VerifyNotice 是认证状态的**动态**提醒(随状态变化出现/消失);
    InfoTip 是**静态**规则说明(看了知道了就关)。两者记忆键各自独立。

    [记忆口径] 与 VerifyNotice 一致:localStorage 按「用户 id + 场景键」,
    换账号互不影响;scene 一变就是另一条提示,不共用关闭状态。
  -->
  <div v-if="visible" class="itip" :data-tone="tone" role="status">
    <el-icon class="itip__icon" :size="16" aria-hidden="true">
      <InfoFilled />
    </el-icon>
    <p class="itip__text"><slot>{{ text }}</slot></p>
    <button type="button" class="itip__close" @click="dismiss">知道了</button>
  </div>
</template>

<script setup>
/**
 * 场景键(必填):同场景共享一条记忆;文案由调用方给,本组件不管内容。
 * tone: info(默认,中性)/ warn(注意类,暖橙描边)。
 */
import { ref } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  scene: { type: String, required: true },
  tone: { type: String, default: 'info' },
  text: { type: String, default: '' },
})

const userStore = useUserStore()

const storageKey = 'infotip:' + (userStore.userInfo?.id ?? 0) + ':' + props.scene
const visible = ref(localStorage.getItem(storageKey) !== '1')

function dismiss() {
  localStorage.setItem(storageKey, '1')
  visible.value = false
}
</script>

<style scoped>
/* 观感减重(2026-10-24):去边框、半透明底、更小的内边距——
   信息条是「看一眼就懂」的辅助物,不该和内容卡抢视觉权重 */
.itip {
  display: flex;
  align-items: flex-start;
  gap: var(--ys-space-2);
  margin: 0 0 var(--ys-space-2);
  padding: var(--ys-space-1) var(--ys-space-2);
  border-radius: var(--ys-radius-md);
  background: var(--ys-surface-2);
  opacity: 0.92;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
}

.itip__icon {
  flex: none;
  margin-top: 2px;
  color: var(--ys-meta);
}

/* warn 态:需要用户留意的规则(如不可逆操作),暖橙描边但不铺底色 */
.itip[data-tone='warn'] {
  color: var(--ys-fg);
}

.itip[data-tone='warn'] .itip__icon {
  color: var(--ys-accent-2);
}

.itip__text {
  flex: 1;
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.itip__close {
  flex: none;
  border: none;
  background: transparent;
  padding: 0;
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-xs);
  color: var(--ys-accent);
  cursor: pointer;
}

.itip__close:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
