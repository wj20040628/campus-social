<template>
  <div class="up">
    <p class="up__note">上传后由管理员人工审核，通过后才会出现在文件库。请先清除文件里的学号、姓名等个人信息。</p>

    <FileQuotaBar :quota="quotaInfo" />

    <p v-if="quotaFailed" class="up__error" role="alert">
      上传额度没加载出来，<button type="button" class="up__retry" @click="loadQuota">重试</button>
    </p>

    <div v-if="quotaLoaded && quotaInfo && !quotaInfo.canUpload" class="up__block">
      <p class="up__block-t">现在还不能上传</p>
      <p class="up__block-p">{{ quotaInfo.reason }}</p>
      <button type="button" class="up__block-b" @click="router.replace('/files')">返回文件库</button>
    </div>

    <form v-else class="up__form" @submit.prevent="submit">
      <div class="up__field">
        <span class="up__label">文件</span>
        <input
          ref="fileRef"
          type="file"
          class="up__file"
          :accept="FILE_ACCEPT"
          @change="onFileChange"
        />
        <button type="button" class="up__pick" @click="fileRef?.click()">
          <span class="up__pick-ic">{{ selected ? fileExtText(extOf(selected.name)) : '＋' }}</span>
          <span class="up__pick-text">
            <span class="up__pick-main">{{ selected ? selected.name : '选择文档' }}</span>
            <span class="up__pick-sub">
              {{ selected ? formatBytes(selected.size) : `支持 PDF / Word / PPT / Excel / TXT / MD，单文件 ≤ ${formatBytes(singleLimit)}` }}
            </span>
          </span>
        </button>
      </div>

      <label class="up__field">
        <span class="up__label">标题</span>
        <input
          v-model="title"
          class="up__input"
          type="text"
          maxlength="40"
          placeholder="比如：计算机学院毕业论文模板"
        />
      </label>

      <div class="up__field">
        <span class="up__label">分类</span>
        <div class="up__cats" role="group" aria-label="文件分类">
          <button
            v-for="c in FILE_CATEGORIES"
            :key="c.value"
            type="button"
            class="up__cat"
            :class="{ 'is-active': category === c.value }"
            @click="category = c.value"
          >
            {{ c.label }}
          </button>
        </div>
      </div>

      <label class="up__field">
        <span class="up__label">简介 <span class="up__optional">选填</span></span>
        <textarea
          v-model="description"
          class="up__textarea"
          rows="3"
          maxlength="100"
          placeholder="这份文件适合谁、包含什么，一句话就够"
        />
      </label>

      <label class="up__consent">
        <input v-model="agreed" type="checkbox" class="up__check" />
        <span>我确认拥有分享这份文件的权利，文件不含他人隐私、付费资料或违规内容</span>
      </label>

      <p v-if="error" class="up__error" role="alert">{{ error }}</p>

      <button type="submit" class="up__submit" :disabled="submitting || !quotaInfo">
        {{ submitting ? '上传中…' : '提交审核' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import FileQuotaBar from '@/components/files/FileQuotaBar.vue'
import * as fileApi from '@/api/files'
import {
  FILE_ACCEPT,
  FILE_CATEGORIES,
  fileExtText,
  formatBytes,
} from '@/constants/fileCategories'

const router = useRouter()

const fileRef = ref(null)
const selected = ref(null)
const title = ref('')
const category = ref('template')
const description = ref('')
const agreed = ref(false)
const error = ref('')
const submitting = ref(false)

const quotaInfo = ref(null)
const quotaLoaded = ref(false)
const quotaFailed = ref(false)

const singleLimit = computed(() => Number(quotaInfo.value?.singleLimitBytes) || 3 * 1024 * 1024)

function extOf(name) {
  const dot = String(name || '').lastIndexOf('.')
  return dot < 0 ? '' : String(name).slice(dot + 1).toLowerCase()
}

async function loadQuota() {
  quotaFailed.value = false
  try {
    quotaInfo.value = await fileApi.quota()
  } catch {
    quotaFailed.value = true
    quotaInfo.value = null
  } finally {
    quotaLoaded.value = true
  }
}

function onFileChange(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  error.value = ''
  const allowed = FILE_ACCEPT.split(',').map((s) => s.replace('.', ''))
  const ext = extOf(file.name)
  if (!allowed.includes(ext)) {
    error.value = '只支持 PDF / Word / PPT / Excel / TXT / MD 文档'
    selected.value = null
    return
  }
  if (file.size > singleLimit.value) {
    error.value = `当前额度单文件最多 ${formatBytes(singleLimit.value)}`
    selected.value = null
    return
  }
  selected.value = file
  if (!title.value.trim()) {
    title.value = file.name.replace(/\.[^.]+$/, '').slice(0, 40)
  }
}

async function submit() {
  if (submitting.value) return
  error.value = ''
  if (!selected.value) {
    error.value = '先选一份文档'
    return
  }
  const cleanTitle = title.value.trim()
  if (cleanTitle.length < 2 || cleanTitle.length > 40) {
    error.value = '标题 2–40 字'
    return
  }
  if (!category.value) {
    error.value = '选一个分类'
    return
  }
  if (!agreed.value) {
    error.value = '请先勾选分享承诺'
    return
  }
  submitting.value = true
  try {
    await fileApi.upload(selected.value, {
      title: cleanTitle,
      category: category.value,
      description: description.value.trim(),
    })
    ElMessage.success('已提交审核，通过后会出现在文件库')
    router.replace('/files?tab=mine')
  } catch (e) {
    error.value = e?.message || '上传失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await loadQuota()
})
</script>

<style scoped src="./FileUpload.css"></style>
