<template>
  <div class="settings">
    <div class="settings-inner">
      <el-card>
        <template #header><span>个人资料</span></template>
        <el-form :model="form" label-position="top">
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" placeholder="别人看到的名字" maxlength="32" />
          </el-form-item>

          <!--
            学校归属拆成两块,因为**写入端点不同**:
            PUT /user/me 只管昵称/学号/头像,学院与年级走 POST /user/school-profile。
            合成一次提交会出现「昵称存上了但学院没存上」的半成功态,用户看不出来。
          -->
          <fieldset class="settings__block">
            <legend class="settings__legend">学校归属</legend>

            <SchoolFields v-model="identity" :invalid="identityInvalid" />

            <p v-if="schoolSaving" class="settings__hint">正在保存学校归属…</p>
            <div v-else class="settings__row">
              <el-button type="primary" :disabled="!identityDirty" @click="saveSchool">
                保存学校归属
              </el-button>
              <span v-if="!identityDirty && identitySaved" class="settings__saved">已保存</span>
            </div>
          </fieldset>

          <el-form-item label="学号">
            <el-input v-model="form.studentNo" placeholder="如 2024001234" maxlength="32" />
          </el-form-item>

          <el-form-item label="头像">
            <el-avatar :size="64" :src="resolveUrl(form.avatarUrl)" />
            <el-upload
              :show-file-list="false"
              :http-request="uploadAvatar"
              accept="image/*"
            >
              <el-button plain class="settings__upload" :icon="Upload">更换头像</el-button>
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
/**
 * 学院 / 年级是**字典下拉**,不是自由文本。
 * 原实现是两个 el-input 自由输入,后果是「计算机学院」与「计科」被算成两个院系,
 * 同院系推荐(AC-14)直接漏人。用户已拍板改字典。
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { ElMessage } from '@/utils/feedback'
import { Upload } from '@element-plus/icons-vue'
import { me as meApi } from '@/api/auth'
import { upload } from '@/api/feed'
import { saveSchoolProfile, readIdentity } from '@/api/dict'
import request from '@/utils/request'
import { resolveUrl } from '@/utils/resolve'
import { IDENTITY_MODEL, SCHOOLS, DICT_FIELDS } from '@/constants/schoolDict'
import { dictWriteError, refreshDictAfterInvalid, applyDictInvalid } from '@/utils/dictError'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import SchoolFields from '@/components/common/SchoolFields.vue'

const userStore = useUserStore()
const dict = useDictStore()
const saving = ref(false)
const schoolSaving = ref(false)
const identitySaved = ref(false)
/** 后端 1040/1041 判定的无效字段,透传给 SchoolFields */
const identityInvalid = ref({})

/** PUT /user/me 管的字段。注意不含 dept / grade —— 它们走 school-profile */
const form = reactive({
  nickname: '', studentNo: '', avatarUrl: '',
})

/** POST /user/school-profile 管的字段。与 IDENTITY_MODEL 同形 */
const identity = ref({ ...IDENTITY_MODEL })
/** 加载时的原始值,用于算 dirty。不记它就没法判断「要不要发请求」 */
const identityOriginal = ref({ ...IDENTITY_MODEL })

async function load() {
  const me = await meApi()
  form.nickname = me.nickname || ''
  // 原实现漏了 studentNo 的回填,导致只要点保存就把学号清空 —— 静默数据丢失
  form.studentNo = me.studentNo || ''
  form.avatarUrl = me.avatarUrl || ''

  // 存量用户可能只有自由文本没有 id,此时用字典反查补一个 id,
  // 补不上就留 null(用户自己重选一次即可),不猜
  const read = readIdentity(me, { depts: dict.depts, grades: dict.grades })
  identity.value = {
    schoolId: me.schoolId || SCHOOLS[0].id,
    deptId: read.deptId,
    gradeId: read.gradeId,
  }
  identityOriginal.value = { ...identity.value }
  identitySaved.value = false
}

const identityDirty = computed(() =>
  identity.value.schoolId !== identityOriginal.value.schoolId
  || identity.value.deptId !== identityOriginal.value.deptId
  || identity.value.gradeId !== identityOriginal.value.gradeId,
)

async function uploadAvatar({ file }) {
  try {
    const data = await upload(file, 'image')
    form.avatarUrl = data.url
    ElMessage.success('头像已更新，记得点保存')
  } catch {
    ElMessage.error('上传失败')
  }
}

async function saveSchool() {
  if (!identity.value.schoolId || !identity.value.deptId || !identity.value.gradeId) {
    ElMessage.warning('学校、学院、年级都选了再保存')
    return
  }
  schoolSaving.value = true
  identityInvalid.value = {}
  try {
    await saveSchoolProfile(identity.value)
    identityOriginal.value = { ...identity.value }
    identitySaved.value = true
    // 同步刷新本地 userInfo,否则头像/昵称栏还显示旧的学院
    await userStore.fetchMe()
    ElMessage.success('学校归属已保存')
  } catch (e) {
    /*
     * 后端 1040/1041(字典已停用)必须落到**字段**上,不能只靠 toast:
     * request.js 已经弹过 message,用户看到的是一个转瞬即逝的提示,
     * 之后表单静默不动 —— 不知道该动哪个下拉。
     *
     * 同时强制重拉字典:失败原因往往是「这个学院已停用」,而用户手里那份
     * 列表里**仍然有**它。只提示不刷新,用户会反复选中同一个无效项。
     */
    const bad = dictWriteError(e)
    if (bad) {
      identityInvalid.value = applyDictInvalid(identity.value, bad)
      await refreshDictAfterInvalid(dict)
    }
  } finally {
    schoolSaving.value = false
  }
}

async function save() {
  saving.value = true
  try {
    // request.js 拦截器已解包 {code,message,data} → 直接返回 data
    const data = await request.put('/user/me', {
      nickname: form.nickname,
      studentNo: form.studentNo,
      avatarUrl: form.avatarUrl,
    })
    userStore.setUserInfo(data)
    ElMessage.success('已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  // 先确保字典到位再 load:readIdentity 的 id 反查依赖字典,
  // 顺序反了会先渲染成「无学院」再跳一下
  await dict.ensureLoaded()
  await load()
})
</script>

<style scoped>
.settings-inner {
  max-width: 640px;
  margin: 0 auto;
  padding: var(--ys-gutter-desktop);
}

/* fieldset 只是语义分组(告诉读屏器「这几项是同一件事」),边框要自己画 */
.settings__block {
  margin: 0 0 var(--ys-space-5);
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
}

.settings__legend {
  padding: 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}

.settings__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}

.settings__hint {
  margin: 0;
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-meta);
}

.settings__saved {
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  color: var(--ys-success);
}

.settings__upload {
  margin-left: var(--ys-space-3);
}
</style>