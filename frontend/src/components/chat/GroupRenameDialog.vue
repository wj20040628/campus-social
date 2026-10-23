<template>
  <!--
    改群名(2026-10-21 从 GroupMenu.vue 内联弹窗抽出)。
    原来只在菜单里能改;现在菜单里那一行右侧要显示"当前群名",
    对话框只负责改,不含入口逻辑。

    G22:输入框不再用 el-input 自带的 show-word-limit ——
    它渲染的是 `3 / 20`(带空格,读起来像分数),而且字号与正文一样大,
    挤在同一基线上像浏览器的字数提示。这里换成自己的计数器:
    写成 `3/20`、11.5px、等宽数字,不随名字长短跳动。
  -->
  <el-dialog v-model="open" title="群聊名称" width="360px" append-to-body>
    <div class="grd__field" :class="{ 'is-focus': focused }">
      <el-input
        v-model="draft"
        :maxlength="MAX"
        placeholder="群名称"
        @focus="focused = true"
        @blur="focused = false"
        @keyup.enter="submit"
      />
      <span class="grd__count" aria-hidden="true">{{ draft.length }}/{{ MAX }}</span>
    </div>
    <p class="grd__hint">群名会显示在每个群成员的会话列表里。</p>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :disabled="!draft.trim()" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

/** 与后端 group.name 的列宽一致 */
const MAX = 20

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 当前群名,打开时回填 */
  name: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'submit'])

const open = ref(false)
const draft = ref('')
/** 自己管聚焦态:要同时改描边和柔光,EP 的 :focus-within 覆盖不到柔光这一层 */
const focused = ref(false)

watch(
  () => props.modelValue,
  (v) => {
    open.value = v
    if (v) draft.value = props.name || ''
  },
  { immediate: true },
)

watch(open, (v) => {
  if (!v && props.modelValue) emit('update:modelValue', false)
})

function submit() {
  const name = draft.value.trim()
  if (!name) return
  open.value = false
  emit('submit', name)
}
</script>

<style scoped>
/* 46px 高、圆角 10,和全站其他输入框一致 */
.grd__field {
  position: relative;
}

.grd__field :deep(.el-input__wrapper) {
  height: 46px;
  /* 右边留 56px 给计数器,名字写满 20 字也不会压到 3/20 上 */
  padding: 0 56px 0 var(--ys-space-3);
  border-radius: var(--ys-radius-lg);
  /* 覆盖 EP 的默认描边(它是 inset box-shadow),改由下面两条自己画 */
  box-shadow: 0 0 0 1px var(--ys-border) inset;
  transition: box-shadow var(--ys-motion-fast) var(--ys-ease-out);
}

.grd__field.is-focus :deep(.el-input__wrapper) {
  box-shadow:
    0 0 0 1px var(--ys-accent) inset,
    0 0 0 3px var(--ys-accent-soft);
}

/* 计数器:框内右侧、垂直居中。字号比正文小一档,不参与换行 */
.grd__count {
  position: absolute;
  top: 50%;
  right: var(--ys-space-3);
  transform: translateY(-50%);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  color: var(--ys-muted);
  pointer-events: none;
}

/* 一句话解释后果,不增加操作步骤 */
.grd__hint {
  margin: var(--ys-space-2) 0 0;
  font-size: var(--ys-text-xs);
  line-height: 1.6;
  color: var(--ys-muted);
}
</style>
