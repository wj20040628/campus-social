/**
 * 学校字典(学院 / 年级)的**唯一字段名出处**
 * ------------------------------------------------------------------
 * 为什么单独一个文件:字典端点由后端并行实现,字段名在实现过程中会微调。
 * 把所有 key 集中在这里,后端回传后**只改本文件**,组件与 store 一行不动。
 *
 * 组件里禁止出现 'deptId' / 'gradeName' 这类字面量 —— 一旦散落,
 * 后端改字段名时要改十几处,漏一处就是「有的页面显示 id 有的显示名字」的静默错误。
 *
 * 上游:docs/api/openapi.yaml §SchoolDictResponse / §SchoolProfileRequest
 */

/** 字典端点路径 */
export const DICT_ENDPOINT = {
  /** 一次拿全院系 + 年级。分开两个端点会让第 1 步多一次往返 */
  school: '/dict/school',
}

/**
 * 字典项与用户资料上的字段名。
 * 读方向(user profile / onboarding complete 的响应)与写方向(请求体)分开列,
 * 因为两者不一定同名 —— 写的是 id,读的时候后端同时给 id 与 name。
 */
export const DICT_FIELDS = {
  /* ---- 字典项:{ id, name, sort } ---- */
  itemId: 'id',
  itemName: 'name',
  itemSort: 'sort',
  /** 年级专有:入学年份。openapi 明确要求用它而不是从 name 截前 4 位 */
  gradeEnrollYear: 'enrollYear',

  /* ---- 字典响应体的键 ---- */
  schoolId: 'schoolId',
  deptsKey: 'depts',
  gradesKey: 'grades',
  /** 总数字段:下拉里显示「共 N 个院系」,失败时不该显示 0 */
  deptTotalKey: 'deptTotal',
  gradeTotalKey: 'gradeTotal',

  /* ---- 用户资料上的学院 / 年级 ---- */
  deptId: 'deptId',
  gradeId: 'gradeId',
  /** 后端下发 name 就直接展示;没下发时前端按 id 反查字典 */
  deptName: 'deptName',
  gradeName: 'gradeName',
  /** 旧字段:后端改造前的自由文本。前端只在 name/id 都拿不到时兜底显示,
   *  且仅用于已存量数据 —— 新写入路径不再产生这两个字段 */
  legacyDept: 'dept',
  legacyGrade: 'grade',
}

/**
 * 写入端点的请求体键名。
 * 引导第 1 步(Onboarding)与注册后补资料(school-profile)共用同一套键,
 * 抽出来是为了让两个调用点不会写出两种拼写。
 */
export const SCHOOL_PROFILE_PAYLOAD = {
  schoolId: 'schoolId',
  deptId: DICT_FIELDS.deptId,
  gradeId: DICT_FIELDS.gradeId,
}

/**
 * 引导第 1 步的本地表单形状。与请求体键名一致,避免映射层。
 *
 * studentNo / nameTail 是**学号核验**字段(选填):引导第 1 步顺路填了就能
 * 一步完成认证,不填也不阻塞(之后可在设置页补)。两者都不参与
 * /onboarding/complete 的请求体(见 useOnboardingFlow.persist 只取前三键),
 * 只用于第 1 步额外触发 POST /user/verify/student-no。
 */
export const IDENTITY_MODEL = {
  schoolId: null,
  deptId: null,
  gradeId: null,
  studentNo: null,
  nameTail: null,
}

/** MVP 单校:schoolId 恒为 1,后端 SchoolProfileRequest 也是这个默认值 */
export const SCHOOLS = [{ id: 1, name: '黄河交通学院' }]

/**
 * 字典拉取失败时的**离线兜底**。
 *
 * [重要]这不是「回退到自由文本」。它是拿本地种子当只读下拉选项,
 * 用户仍然只能选一个**固定选项**,写回去的仍然是 id,不会产生新的自由文本。
 * 也就是说:后端字典没上线时,同院系推荐(AC-14)照样不会漏人。
 *
 * 与字典表的种子数据同源(黄河交通学院招生网 2026 年公示的招生学院)。
 * 后端上线后自动不用了。
 */
export const FALLBACK_DEPTS = [
  '智慧交通与智能建造工程学院',
  '智能车辆与新能源工程学院',
  '智能制造与电气工程学院',
  '人工智能学院',
  '数字经济与智慧管理学院',
  '马克思主义学院',
  '艺术与设计学院',
].map((name, i) => ({ id: i + 1, name }))

/**
 * 年级兜底(2026 年在校生:本科四年/大专三年,2023 级是最老的大四,
 * 2023 级之前均已毕业离校)。展示层会补「级」字。
 */
export const FALLBACK_GRADES = ['2023', '2024', '2025', '2026']
  .map((y) => ({ id: Number(y), name: `${y}级`, enrollYear: Number(y) }))

/** 引导总步数,进度条 aria-valuemax 用它 */
export const TOTAL_STEPS = 3

/**
 * 兜底:后端未下发 minSelect/maxSelect 时用这两个值。
 * **只在后端字段缺失时兜底**,不覆盖后端 —— 写死就是「沉默逻辑错误」:
 * 后端把上限调到 8,前端还按 5 拦,用户会卡在选不满 5 个。
 */
export const FALLBACK_MIN_SELECT = 3
export const FALLBACK_MAX_SELECT = 12

/**
 * 年级展示名的统一算法。
 *
 * 优先用 `enrollYear` 而不是从 name 里截前 4 位 —— openapi §GradeItem 明确写了
 * 「截字符串等于把解析规则复制一份到每一处消费方」:「2024级」「2024 级」
 * 「2024级本科」都能解析出 2024,而正则只认得住其中一种写法。
 *
 * 拿不到 enrollYear 时退回「补级字」:后端若下发「2024」,直接显示会很突兀
 * (其他页面都写「2024 级」)。**只动展示,不动数据** —— 改的是呈现不是事实。
 *
 * @param {string|number} name 后端下发的 name
 * @param {number} [enrollYear] 后端下发的 enrollYear
 */
export function formatGradeName(name, enrollYear) {
  if (Number.isFinite(Number(enrollYear)) && Number(enrollYear) > 0) {
    return `${Number(enrollYear)}级`
  }
  const s = String(name ?? '').trim()
  if (!s) return ''
  return /级$/.test(s) ? s : `${s}级`
}