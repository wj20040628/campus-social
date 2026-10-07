<template>
  <div class="settings">
    <div class="settings-inner">
      <el-card>
        <template #header><span>个人资料</span></template>
        <el-form :model="form" label-width="80px">
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" placeholder="昵称" />
          </el-form-item>
          <el-form-item label="年级">
            <el-input v-model="form.grade" placeholder="如 大二" />
          </el-form-item>
          <el-form-item label="院系">
            <el-input v-model="form.dept" placeholder="如 计算机学院" />
          </el-form-item>
          <el-form-item label="学号">
            <el-input v-model="form.studentNo" placeholder="如 2024001234" />
          </el-form-item>
          <el-form-item label="头像">
            <el-avatar :size="64" :src="resolveUrl(form.avatarUrl)" />
            <el-upload
              :show-file-list="false"
              :http-request="uploadAvatar"
              accept="image/*"
            >
              <el-button plain style="margin-left:12px" icon="Upload">更换头像</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { me as meApi } from '@/api/auth'
import { upload } from '@/api/feed'
import request from '@/utils/request'
import { resolveUrl } from '@/utils/resolve'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const saving = ref(false)

const form = reactive({
  nickname: '', grade: '', dept: '', studentNo: '', avatarUrl: '',
})

async function load() {
  const me = await meApi()
  form.nickname = me.nickname || ''
  form.grade = me.grade || ''
  form.dept = me.dept || ''
  form.avatarUrl = me.avatarUrl || ''
}

async function uploadAvatar({ file }) {
  try {
    const data = await upload(file, 'image')
    form.avatarUrl = data.url
    ElMessage.success('头像已更新,记得点保存')
  } catch {
    ElMessage.error('上传失败')
  }
}

async function save() {
  saving.value = true
  try {
    const data = await meUpdate()
    userStore.setUserInfo(data)
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

async function meUpdate() {
  // request.js 拦截器已解包 {code,message,data} → 直接返回 data
  return request.put('/user/me', {
    nickname: form.nickname,
    grade: form.grade,
    dept: form.dept,
    studentNo: form.studentNo,
    avatarUrl: form.avatarUrl,
  })
}

onMounted(load)
</script>

<style scoped>
.settings-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: 20px;
}
</style>
