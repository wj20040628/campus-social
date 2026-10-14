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
          -->
          <fieldset class="sp__block">
            <legend class="sp__legend">学校归属</legend>

            <SchoolFields v-model="identity" :invalid="identityInvalid" />

            <p v-if="schoolSaving" class="sp__hint">正在保存学校归属…</p>
            <div v-else class="sp__row">
              <el-button type="primary" :disabled="!identityDirty" @click="saveSchool">
                保存学校归属
              </el-button>
              <span v-if="!identityDirty && identitySaved" class="sp__saved">已保存</span>
            </div>
            <p class="sp__hint sp__hint--note">学校归属单独保存,昵称 / 学号 / 头像用下方「保存资料」。</p>
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

<style scoped>
.sp {
  min-height: 100dvh;
  background: var(--ys-bg);
  display: flex;
  flex-direction: column;
}

/* 自绘顶栏:与全站顶栏同高,ownTop 让 MainLayout 不叠第二层 */
.sp__bar {
  position: sticky;
  top: 0;
  z-index: var(--ys-z-sticky);
  height: var(--ys-topbar-h);
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  padding: 0 var(--ys-space-4);
  background: var(--ys-surface);
  border-bottom: 1px solid var(--ys-border);
}
.sp__bar-btn {
  width: var(--ys-touch-min);
  height: var(--ys-touch-min);
  display: grid;
  place-items: center;
  border: none;
  background: transparent;
  color: var(--ys-fg-2);
  border-radius: var(--ys-radius-lg);
  cursor: pointer;
}
.sp__bar-btn:hover { background: var(--ys-surface-2); }
.sp__bar-title {
  margin: 0;
  font-size: var(--ys-text-lg);
  line-height: var(--ys-lh-lg);
  font-weight: var(--ys-weight-semibold);
  color: var(--ys-fg);
}

.sp-inner {
  max-width: 640px;
  margin: 0 auto;
  width: 100%;
  padding: var(--ys-gutter-desktop);
}

.sp__card { margin-bottom: var(--ys-space-4); }

/* 表单块(fieldset 语义分组),边框自己画 */
.sp__block {
  margin: 0 0 var(--ys-space-5);
  padding: var(--ys-space-4);
  border: 1px solid var(--ys-border);
  border-radius: var(--ys-radius-lg);
}
.sp__legend {
  padding: 0 var(--ys-space-2);
  font-size: var(--ys-text-sm);
  line-height: var(--ys-lh-sm);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg-2);
}
.sp__row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
}
.sp__hint {
  margin: 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-meta);
}
/* 提交时机说明:比状态 hint 更靠下、更弱化,只解释「哪些一起存」 */
.sp__hint--note {
  margin: var(--ys-space-2) 0 0;
}
.sp__saved {
  font-size: var(--ys-text-sm);
  color: var(--ys-success);
}
.sp__upload {
  margin-left: var(--ys-space-3);
}

.sp__switch-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--ys-space-4);
  padding: var(--ys-space-2) 0;
}
.sp__switch-label {
  font-size: var(--ys-text-base);
  line-height: var(--ys-lh-base);
  font-weight: var(--ys-weight-medium);
  color: var(--ys-fg);
}
.sp__switch-hint {
  margin: var(--ys-space-1) 0 0;
  font-size: var(--ys-text-sm);
  color: var(--ys-muted);
  max-width: 42ch;
}

/* 桌面(≥1024px):内容列放宽,顶栏更舒展。移动端完全不受影响。
   桌面点 Profile 页「编辑资料」直接落到本页,顶栏保留返回(router.back() 回个人主页)。 */
@media (min-width: 1024px) {
  .sp-inner {
    max-width: 720px;
    padding: var(--ys-space-6) var(--ys-space-4);
  }
  .sp__bar {
    padding: 0 var(--ys-space-6);
  }
}
</style>
