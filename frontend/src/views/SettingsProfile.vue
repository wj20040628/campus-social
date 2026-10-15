<template>
  <div class="sp">
    <!--
      「我的」页的编辑资料独立页(把原 Settings.vue 里的资料表单抽出来)。
      点「我的」页概览卡的「编辑资料」进这里 —— 平时点 TabBar「我的」
      看到的是帖子列表,资料编辑收进这单独一页,首屏不挤表单。
      自带顶栏(ownTop):左返回、中标题「编辑资料」,与全站顶栏不叠双栏。
    -->
    <header class="sp__bar">
      <button type="button" class="sp__bar-btn" aria-label="返回" @click="router.back()">
        <el-icon :size="22"><ArrowLeft /></el-icon>
      </button>
      <h1 class="sp__bar-title">编辑资料</h1>
    </header>

    <div class="sp-inner">
      <el-card class="sp__card">
        <template #header><span>个人资料</span></template>
        <el-form :model="form" label-position="top">
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" placeholder="别人看到的名字" maxlength="32" />
          </el-form-item>

          <!--
            学校归属拆成两块,因为**写入端点不同**:
            PUT /user/me 只管昵称/学号/头像,学院与年级走 POST /user/school-profile。
            合成一次提交会出现「昵称存上了但学院没存上」的半成功态,用户看不出来。
            桌面端外层用 div(block-group),由 CSS 软化成浅底块;移动端原生 fieldset 边框
            在温暖设计里太生硬,一并改掉,见 SettingsProfile.css 的 .sp__block-group。
          -->
          <div class="sp__block-group" role="group" aria-label="学校归属">
            <div class="sp__block">

            <SchoolFields v-model="identity" :invalid="identityInvalid" />

            <p v-if="schoolSaving" class="sp__hint">正在保存学校归属…</p>
            <div v-else class="sp__row">
              <el-button type="primary" :disabled="!identityDirty" @click="saveSchool">
                保存学校归属
              </el-button>
              <span v-if="!identityDirty && identitySaved" class="sp__saved">已保存</span>
            </div>
            <p class="sp__hint sp__hint--note">学校归属单独保存,昵称 / 学号 / 头像用下方「保存资料」。</p>
            </div>
          </div>

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
              <el-button plain class="sp__upload" :icon="Upload">更换头像</el-button>
            </el-upload>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存资料</el-button>
          </el-form-item>
        </el-form>

        <el-divider content-position="left">消息与隐私</el-divider>

        <div class="sp__switch-row">
          <div class="sp__switch-text">
            <div class="sp__switch-label">允许陌生人给我发消息</div>
            <p class="sp__switch-hint">
              关闭后,未关注你的人无法主动给你发私信(你仍可主动找别人)。切换即时生效,无需点保存。
            </p>
          </div>
          <el-switch
            v-model="strangerMsg"
            :loading="strangerSaving"
            @change="onStrangerMsgChange"
          />
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
/**
 * 编辑资料独立页。原 Settings.vue 的资料表单整体搬来,
 * 逻辑(save / saveSchool / uploadAvatar / 陌生人开关)与原来一致,
 * 只换了容器与类名前缀(sp__ 而非 settings__),顶栏改自绘。
 */
import { reactive, ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from '@/utils/feedback'
import { ArrowLeft, Upload } from '@element-plus/icons-vue'
import { me as meApi, setStrangerMsg } from '@/api/auth'
import { upload } from '@/api/feed'
import { saveSchoolProfile, readIdentity } from '@/api/dict'
import { verifyStatus } from '@/api/verify'
import request from '@/utils/request'
import { resolveUrl } from '@/utils/resolve'
import { IDENTITY_MODEL, SCHOOLS } from '@/constants/schoolDict'
import { dictWriteError, refreshDictAfterInvalid, applyDictInvalid } from '@/utils/dictError'
import { useUserStore } from '@/stores/user'
import { useDictStore } from '@/stores/dict'
import SchoolFields from '@/components/common/SchoolFields.vue'

const userStore = useUserStore()
const dict = useDictStore()
const router = useRouter()

const saving = ref(false)
const schoolSaving = ref(false)
const identitySaved = ref(false)
const strangerMsg = ref(true)
const strangerSaving = ref(false)
const identityInvalid = ref({})

/** PUT /user/me 管的字段。注意不含 dept / grade —— 它们走 school-profile */
const form = reactive({
  nickname: '', studentNo: '', avatarUrl: '',
})

/** POST /user/school-profile 管的字段。与 IDENTITY_MODEL 同形 */
const identity = ref({ ...IDENTITY_MODEL })
const identityOriginal = ref({ ...IDENTITY_MODEL })

async function load() {
  const me = await meApi()
  form.nickname = me.nickname || ''
  /**
   * 学号**必须从 /user/verify/status 拿**,不能期待 /user/me:
   * UserVO 同时用于关注列表 / 搜索等多人场景,后端刻意不在里面放学号(隐私)。
   */
  let studentNo = ''
  try {
    const vs = await verifyStatus()
    studentNo = vs?.studentNo || ''
  } catch {
    /* 认证状态拉不到不影响其它字段 */
  }
  form.studentNo = studentNo
  form.avatarUrl = me.avatarUrl || ''
  strangerMsg.value = me.strangerMsgEnabled !== false

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
    ElMessage.success('头像已更新,记得点保存')
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
    await userStore.fetchMe()
    ElMessage.success('学校归属已保存')
  } catch (e) {
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

async function onStrangerMsgChange(val) {
  strangerSaving.value = true
  try {
    await setStrangerMsg(val)
    ElMessage.success(val ? '已允许陌生人发消息' : '已关闭,陌生人无法主动找你')
  } catch {
    strangerMsg.value = !val
  } finally {
    strangerSaving.value = false
  }
}

onMounted(async () => {
  await dict.ensureLoaded()
  await load()
})
</script>

<style scoped src="./SettingsProfile.css"></style>
