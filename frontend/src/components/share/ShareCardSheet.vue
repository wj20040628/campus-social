<template>
  <!--
    分享卡片预览弹层(2026-10-25):复用全站 ActionSheet 形态,
    内容 = 卡片预览图 + 保存/分享按钮。卡片由 utils/shareCard 的 canvas 生成,
    本组件只负责「打开时生成、点按钮时保存」的编排。
  -->
  <ActionSheet
    :model-value="open"
    title="分享给朋友"
    @update:model-value="emit('update:open', $event)"
  >
    <div class="share">
      <p v-if="loading" class="share__status">卡片生成中…</p>
      <template v-else-if="dataUrl">
        <img class="share__img" :src="dataUrl" alt="分享卡片预览" />
        <button type="button" class="share__save" @click="save">
          {{ saving ? '保存中' : '保存 / 分享' }}
        </button>
        <!--
          复制链接（2026-10-28）：文件库分享此前是 window.prompt 兜底，
          手机上根本选不中文本。卡片旁边给一条可点的链接行，
          点「复制链接」走 utils/clipboard（http 源也能复制）。
        -->
        <button v-if="link" type="button" class="share__copy" @click="copy">
          {{ copied ? '已复制链接' : '复制链接' }}
        </button>
        <p v-if="link" class="share__link">{{ link }}</p>
        <p class="share__tip">保存到相册，发到微信群、QQ 群；扫码的人不用登录就能看</p>
      </template>
      <p v-else class="share__status share__status--err">卡片没生成出来，关掉再试一次</p>
    </div>
  </ActionSheet>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from '@/utils/feedback'
import ActionSheet from '@/components/mobile/ActionSheet.vue'
import { drawShareCard, shareOrDownload } from '@/utils/shareCard'
import { copyText } from '@/utils/clipboard'

const props = defineProps({
  open: { type: Boolean, default: false },
  /**
   * 卡片内容 spec(见 utils/shareCard.drawShareCard 的入参)。
   * 父组件从已加载的帖子/活动数据里拼好传入;null/空对象时打开只会显示失败态。
   */
  spec: { type: Object, default: null },
  /** 卡片同源的分享链接。传了就多一个「复制链接」按钮 */
  link: { type: String, default: '' },
})
const emit = defineEmits(['update:open', 'shared'])

const loading = ref(false)
const saving = ref(false)
const dataUrl = ref('')
/** 复制成功态:按钮文案短暂变「已复制链接」,比 toast 更贴近手指位置 */
const copied = ref(false)
let canvas = null

watch(
  [() => props.open, () => props.spec],
  async ([open]) => {
    if (!open || !props.spec) return
    loading.value = true
    dataUrl.value = ''
    copied.value = false
    try {
      canvas = await drawShareCard(props.spec)
      dataUrl.value = canvas.toDataURL('image/png')
    } catch {
      canvas = null
    } finally {
      loading.value = false
    }
  },
)

async function copy() {
  if (!props.link) return
  const ok = await copyText(props.link)
  if (!ok) {
    ElMessage.warning('这个浏览器不让复制，长按链接手动选中')
    return
  }
  copied.value = true
  ElMessage.success('链接已复制')
  setTimeout(() => { copied.value = false }, 2000)
}

async function save() {
  if (!canvas || saving.value) return
  saving.value = true
  try {
    const result = await shareOrDownload(canvas, props.spec.fileName || 'yousheng-share.png')
    if (result === 'failed') {
      ElMessage.error('保存失败，再试一次')
    } else {
      if (result === 'downloaded') {
        ElMessage.success('图片已保存/下载')
      }
      // 只有真的保存/分享出去才算一次；打开面板又关掉不算
      emit('shared', result)
    }
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.share {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--ys-space-3);
  padding: var(--ys-space-3) var(--ys-space-4) var(--ys-space-2);
}

.share__img {
  width: 100%;
  max-width: 320px;
  border-radius: 14px;
  border: 1px solid var(--ys-border);
  box-shadow: var(--ys-shadow-1);
}

.share__save {
  width: 100%;
  max-width: 320px;
  min-height: var(--ys-touch-min);
  border: 0;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-md);
  font-weight: var(--ys-weight-semibold);
  cursor: pointer;
}

.share__save:active {
  background: var(--ys-accent-active);
}

.share__tip {
  margin: 0;
  font-size: var(--ys-text-xs);
  line-height: var(--ys-lh-xs);
  color: var(--ys-muted);
  text-align: center;
}

.share__copy {
  width: 100%;
  max-width: 320px;
  min-height: var(--ys-touch-min);
  border: 1px solid var(--ys-accent);
  border-radius: var(--ys-radius-full);
  background: var(--ys-surface);
  color: var(--ys-accent);
  font-family: var(--ys-font-body);
  font-size: var(--ys-text-sm);
  font-weight: var(--ys-weight-semibold);
  cursor: pointer;
}

.share__copy:active {
  background: var(--ys-accent-soft);
}

.share__link {
  max-width: 320px;
  margin: 0;
  font-size: var(--ys-text-xs);
  color: var(--ys-muted);
  text-align: center;
  word-break: break-all;
}

.share__status {
  margin: var(--ys-space-5) 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
}

.share__status--err {
  color: var(--ys-danger);
}
</style>
