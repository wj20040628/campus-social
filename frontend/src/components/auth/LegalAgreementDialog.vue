<template>
  <!--
    协议弹层（2026-10-20）。

    ## 为什么单独抽组件
    注册页要弹《用户协议》《隐私政策》，登录页、设置/关于页未来也可能弹同一份，
    抽出来一处维护、到处引用，也避免 Register.vue 行数膨胀顶破 300 行门禁。

    ## 为什么用 open(doc) 而不是 v-model 直接控制
    与 StudentNoAppealDialog 同款写法：父组件拿 ref 调 open('user' | 'privacy')，
    由组件内部决定显示哪一份。勾选框里的两个协议名各自带 doc 调起，互不干扰。

    ## 文本来源
    以用户提供的精简版为底，补充了注销交叉引用、邮箱/第三方大模型用途、
    协议变更、存储期限四处理性条款；仍保持简练、无 emoji、无紫粉渐变。
  -->
  <el-dialog
    v-model="open_"
    :title="current.title"
    :width="isMobile ? '92%' : '520px'"
    append-to-body
  >
    <div class="la">
      <p v-for="(p, i) in current.paragraphs" :key="i" class="la__p">{{ p }}</p>
    </div>
    <template #footer>
      <el-button type="primary" @click="open_ = false">我已知晓</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useIsMobile } from '@/composables/useBreakpoint'
import { LEGAL_DOCS } from '@/constants/legalDocs'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])

const isMobile = useIsMobile()
const open_ = ref(false)
/** 当前展示哪一份：'user' = 用户协议，'privacy' = 隐私政策 */
const docType = ref('user')

// 正文统一来自 @/constants/legalDocs，弹窗与独立路由页共用同一份，避免两处不同步
const current = computed(() => LEGAL_DOCS[docType.value] || LEGAL_DOCS.user)

function open(doc = 'user') {
  docType.value = doc === 'privacy' ? 'privacy' : 'user'
  open_.value = true
}

defineExpose({ open })
</script>

<style scoped src="./LegalAgreementDialog.css"></style>
