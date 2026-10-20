import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadToOss } from '@/composables/useOssUpload'
import { updateMe, setCover } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { resolveUrl } from '@/utils/resolve'

/**
 * 个人主页的「点头像换头像 / 点封面换封面」（2026-10-20）。
 *
 * ## 为什么单独一个组合式函数
 * Profile.vue 已经贴着单文件 400 行门禁，而这套逻辑有二十来行外加三条注释；
 * 塞回视图只会把第 401 行挤出来。更要紧的是它同时动三处状态
 * （页面上的 user、全局 userStore、localStorage），视图里摊开写极易漏掉一处
 * ——漏 userStore 的表现是「主页换了头像，但顶栏还是旧头像」。
 *
 * ## 为什么用 document.createElement 造 input，而不是模板里放 <input hidden>
 * 模板方案要在视图里多一个 ref、多一个 change 处理函数、并且「头像」与「封面」
 * 得各来一份（或共用一个再区分来源）。程序化创建把这三样都收进函数内部，
 * 视图只留两个 @click。移动端 Safari/微信内核对「用户手势内同步调用 click()」
 * 是放行的，这里没有 await 夹在中间，不触发拦截。
 *
 * ## 上传失败的两层兜底
 * uploadToOss 内部已经做过 OSS→服务端降级；它抛出来只剩「未登录」与
 * 「服务端兜底也失败」两种，都在这里提示一次。不提示的具体错误码交给
 * request.js 的全局拦截器（那里已经弹过 toast），此处只补一句更具体的动作说明。
 */
export function useProfileMedia(user) {
  const userStore = useUserStore()
  const avatarBusy = ref(false)
  const coverBusy = ref(false)

  /** 当前封面（空串表示没设置，走默认渐变） */
  const hasCover = computed(() => !!(user.value?.coverUrl || '').trim())

  /**
   * 封面的内联样式。用 background-image 而不是 <img>：
   * 封面要铺满 260px、两端（顶部与内容区）都必须裁切而不是留白，
   * background-size:cover + center 一行搞定，<img> 要额外包一层 overflow:hidden。
   */
  const coverStyle = computed(() => (hasCover.value
    ? { backgroundImage: `url("${resolveUrl(user.value.coverUrl)}")` }
    : null))

  /**
   * 把服务端返回的最新 VO 同步到页面与全局 store（顶栏/抽屉的头像都读 store）。
   *
   * <p>**只合并有值的字段**：PUT 接口回的是 `AuthServiceImpl.toVo`，
   * 里面 **不含** `postCount / likeCount / favoriteCount / likedCount`
   * —— 那四个由 `GET /user/me` 在 controller 里现查现填。无脑展开的话
   * 会把它们覆盖成 null，用户看到的现象是「换完头像，主页 Tab 上的数字全变 0」，
   * 而且刷新一下又好了，最难查的那类。
   */
  function sync(vo, force = []) {
    if (!vo) return
    const patch = {}
    for (const [k, v] of Object.entries(vo)) {
      if (v !== null && v !== undefined) patch[k] = v
    }
    // 少数字段**可以被清空成 null**（封面点「恢复默认」就是），
    // 上面那条「跳过 null」的规则会把这次清空吞掉 —— 用户点完毫无反应。
    // 所以这类字段由调用方点名强制写回。
    for (const k of force) patch[k] = vo[k] ?? null
    if (user.value) user.value = { ...user.value, ...patch }
    // 只合并到已有 userInfo 上：登录态里还有 role 等 /me 之外的判据字段
    userStore.setUserInfo({ ...(userStore.userInfo || {}), ...patch })
  }

  /** 造一个一次性文件选择器，resolve 出用户选的文件（取消则 resolve null） */
  function pickFile() {
    return new Promise((resolve) => {
      const el = document.createElement('input')
      el.type = 'file'
      el.accept = 'image/*'
      // 不挂到 body 也能 click()，但部分老 WebView 要求元素在文档里才弹选择器
      el.style.position = 'fixed'
      el.style.left = '-9999px'
      document.body.appendChild(el)
      el.addEventListener('change', () => {
        const file = el.files && el.files[0] ? el.files[0] : null
        el.remove()
        resolve(file)
      })
      // 用户点「取消」时要给一个出口：现代浏览器派发 cancel 事件，
      // 老浏览器则派发一次 files 为空的 change。两条路都收口到 resolve(null)，
      // 否则这个 Promise 永远悬着，按钮按了没反应且不报错。
      el.addEventListener('cancel', () => {
        el.remove()
        resolve(null)
      })
      el.click()
    })
  }

  /**
   * 换头像：上传 → PUT /user/me（只带 avatarUrl，其余字段为 undefined 时
   * 后端按「本次不改」处理）。
   */
  async function changeAvatar() {
    if (avatarBusy.value) return
    const file = await pickFile()
    if (!file) return
    avatarBusy.value = true
    try {
      const data = await uploadToOss(file, 'image')
      const vo = await updateMe({ avatarUrl: data.url })
      sync(vo)
      ElMessage.success('头像已更新')
    } catch (e) {
      // 401 说明登录过期，全局拦截器已处理跳转，这里不再叠一句误导性提示
      if (e?.businessCode !== 401) ElMessage.error('头像没换成，再试一次')
    } finally {
      avatarBusy.value = false
    }
  }

  /** 换封面：上传 → PUT /user/cover */
  async function changeCover() {
    if (coverBusy.value) return
    const file = await pickFile()
    if (!file) return
    coverBusy.value = true
    try {
      const data = await uploadToOss(file, 'image')
      const vo = await setCover(data.url)
      // coverUrl 必须强制写回：sync 默认跳过 null，而「换过一次又恢复默认」时
      // 返回的正是 null
      sync(vo, ['coverUrl'])
      ElMessage.success('封面已更新')
    } catch (e) {
      if (e?.businessCode !== 401) ElMessage.error('封面没换成，再试一次')
    } finally {
      coverBusy.value = false
    }
  }

  /** 恢复默认封面：传 null，后端落成 NULL */
  async function resetCover() {
    if (coverBusy.value) return
    coverBusy.value = true
    try {
      const vo = await setCover(null)
      sync(vo, ['coverUrl'])
      ElMessage.success('已恢复默认封面')
    } catch (e) {
      if (e?.businessCode !== 401) ElMessage.error('恢复失败，再试一次')
    } finally {
      coverBusy.value = false
    }
  }

  return { avatarBusy, coverBusy, hasCover, coverStyle, changeAvatar, changeCover, resetCover }
}
