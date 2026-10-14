import { ref } from 'vue'
import dayjs from 'dayjs'
import { createPost } from '@/api/feed'
import { createActivity } from '@/api/circle'
import { ElMessage } from '@/utils/feedback'

/**
 * 圈子详情 —— 发帖 / 发活动两条写路径。
 * 从 CircleDetail.vue 抽出,因为它自带草稿态、校验、上传态和错误文案,
 * 内联在视图里会把文件推过 300 行门禁;而且这两条路径与读取逻辑无关。
 */
export function useCircleWrites(circleId, canCreate, reloadPosts, reloadActivities, onActivityCreated, actForm, postContent, postMedia, postError, posting, uploading) {
  /* ---------------- 发帖 ---------------- */
  /* postContent/postMedia/posting/uploading/postError 由调用方(视图)传入,
     这里不再自己 ref() 建一份 —— 否则模板 v-model 绑的是调用方那份,
     这里再建同名的 ref 会遮蔽调用方的,等于改错目标。 */

  async function submitPost() {
    if (posting.value) return
    posting.value = true
    postError.value = ''
    try {
      await createPost({
        content: postContent.value,
        media: postMedia.value.length ? JSON.stringify(postMedia.value) : null,
        circleId: Number(circleId.value),
      })
      postContent.value = ''
      postMedia.value = []
      await reloadPosts()
      ElMessage.success('发到圈子了')
    } catch {
      // **内容保留**(§3):不清空 textarea。发失败就清,等于让用户重打一遍
      postError.value = '没发出去,再点一次发布'
    } finally {
      posting.value = false
    }
  }

  /* ---------------- 发活动 ---------------- */
  const showActForm = ref(false)
  const creatingAct = ref(false)
  const actError = ref('')

  const EMPTY_FORM = () => ({ title: '', capacity: 0, startTime: null, endTime: null })

  /** 兜底:调用方没传 actForm 时(老调用),内部建一份 */
  const _actForm = actForm ?? ref({ title: '', capacity: 0, startTime: null, endTime: null })

  function openActForm() {
    actError.value = ''
    showActForm.value = true
  }

  async function submitActivity() {
    if (creatingAct.value) return
    const f = _actForm.value

    // 校验错误写回表单区,不弹 toast:抽屉里弹出的 toast 会盖住提交按钮,
    // 用户看不到自己错在哪一行
    if (!f.title.trim()) {
      actError.value = '先写个活动标题'
      return
    }
    if (!f.startTime || !f.endTime) {
      actError.value = '开始和结束时间都要选'
      return
    }
    // 关键修复:el-date-picker 没指定 value-format 时给的是 dayjs 实例,
    // 但如果用户在输入框里手打了文本(或 value 来自字符串),f.startTime 可能是
    // 纯字符串。先用 dayjs() 包一层,拿到真正的实例再 .format(),就不会报
    // "format is not a function"
    let startD, endD
    try {
      startD = dayjs(f.startTime)
      endD = dayjs(f.endTime)
    } catch {
      actError.value = '时间格式不对,重新选一下'
      return
    }
    if (!startD.isValid() || !endD.isValid()) {
      actError.value = '开始和结束时间都要选'
      return
    }
    if (endD.isBefore(startD)) {
      actError.value = '结束时间要晚于开始时间'
      return
    }

    creatingAct.value = true
    actError.value = ''
    try {
      await createActivity({
        title: f.title.trim(),
        circleId: Number(circleId.value),
        capacity: f.capacity || 0,
        // 后端收东八区 naive datetime(YYYY-MM-DDTHH:mm:ss,不带 Z)。
        // 用 toISOString() 会发 UTC(带 Z),Spring 解析进 LocalDateTime 取到 UTC 墙钟,
        // 存进东八区 naive 字段就整体偏 -8h(16:00 存成 08:00)。
        startTime: startD.format('YYYY-MM-DDTHH:mm:ss'),
        endTime: endD.format('YYYY-MM-DDTHH:mm:ss'),
      })
      showActForm.value = false
      _actForm.value = EMPTY_FORM()
      await reloadActivities()
      // 发完活动切到活动 Tab:不然用户在帖子 Tab 发完,看不到自己发的在哪
      onActivityCreated?.()
      ElMessage.success('活动发出去了')
    } catch (e) {
      // 同上:抽屉不关,用户填的东西还在。
      // request.js 的拦截器把所有失败(reject 的是原始 axios error,不是业务 Error)
      // 都会冒到这儿:按优先级取「后端业务文案 > 401 刷新失败的文案 > 兜底」。
      // 422/403 的业务 message 在 error.response.data.message,直接说清被什么拦住了。
      const status = e?.response?.status
      const msg = e?.response?.data?.message
      let text
      if (status === 401) text = '登录过期了,发之前请重新登录'
      else if (msg && !/操作失败|网络错误/.test(msg)) text = `${msg}(再试一次)`
      else if (e?.message) text = e.message
      else text = '没发出去,再点一次'
      actError.value = text
      // 同时 console 一份,方便排查「前端控制台没反应」这种静默失败
      console.warn('[活动发布失败]', { status, msg, cause: e?.cause, requestUrl: e?.config?.url }, e)
    } finally {
      creatingAct.value = false
    }
  }

  return {
    postContent,
    postMedia,
    posting,
    uploading,
    postError,
    submitPost,
    showActForm,
    creatingAct,
    actError,
    actForm: _actForm,
    openActForm,
    submitActivity,
  }
}