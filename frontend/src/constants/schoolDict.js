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
  /** 专业级联下拉：按学院 + 本专科过滤。GET /dict/majors?deptId=&level= */
  majors: '/dict/majors',
  /** 一次拉全校专业(挂载预取用,前端按学院+层次过滤)。GET /dict/majors/all */
  majorsAll: '/dict/majors/all',
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

  /* ---- 专业字典项与用户资料上的专业 ---- */
  /** 专业字典 id，指向 school_major.id；层次(本/专科)由专业派生，不冗余存储 */
  majorId: 'majorId',
  /** 专业名称。后端在写回执(SchoolProfileVO)里带，但 user 表不冗余存名称，
   *  历史/列表场景如需展示可后续接「按 id 查专业名」端点 */
  majorName: 'majorName',
  /** 专业列表端点响应体的键(GET /dict/majors 返回数组) */
  majorsKey: 'majors',
  /** 专业字典项字段 */
  majorItemId: 'id',
  majorItemName: 'name',
  majorItemCode: 'code',
  majorItemLevel: 'level',
  majorItemSort: 'sort',
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
  majorId: DICT_FIELDS.majorId,
}

/**
 * 引导 complete 请求体里「学校归属」的形状(只取三键)。
 *
 * 注册页已经收过学校/学院/年级并落库,引导页不再重复收(问题 2 的修复):
 * 进入引导时 identity 直接从 userStore.userInfo 预填,这里只作为请求体的键名来源,
 * 避免再写一层名字映射。学号(studentNo/nameTail)已移出引导,
 * 改由设置页的校园信息入口处理。
 */
export const IDENTITY_MODEL = {
  schoolId: null,
  /** 校区 1东 2西(2026-10-25):与院系完全解耦——东西校区都有全部院系,
   *  不做任何级联/绑定校验,是学校归属下的独立自选字段 */
  campus: null,
  deptId: null,
  /** 本专科：1=本科 2=专科。注册「本专科」下拉的取值，
   *  同时是专业级联下拉的过滤维度之一(与 deptId 共同决定可选专业) */
  level: null,
  gradeId: null,
  /** 专业字典 id，指向 school_major.id；级联下拉在「学院 + 本专科」选定后出现 */
  majorId: null,
}

/**
 * 校区下拉选项(2026-10-25)。两值枚举不进 school_dept 字典表:
 * 校区与院系无绑定关系(用户拍板:东西校区都有),值域恒定无需运营维护。
 */
export const CAMPUS_OPTIONS = [
  { value: 1, label: '东校区' },
  { value: 2, label: '西校区' },
]

/** user.campus 值 → 展示名;未知值(含 null)返回空串 */
export function campusName(campus) {
  const hit = CAMPUS_OPTIONS.find((c) => c.value === Number(campus))
  return hit ? hit.label : ''
}

/**
 * 本专科下拉选项(注册「本专科」下拉)。
 * 值取 1/2 与 school_major.level 同口径(本科国标码/专科校内码)。
 */
export const LEVEL_OPTIONS = [
  { value: 1, label: '本科' },
  { value: 2, label: '专科' },
]

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

/**
 * 学号层次标记(位 11-12)与所选本专科是否一致(注册页即时校验用)。
 *
 * 学号位布局(后端 StudentNoParser / CampusVerifyServiceImpl 同款):
 * 位 1-2 入学年 / 位 3-6 专业大类 / 位 7-8 专业位号 / 位 9-10 班级 /
 * 位 11-12 层次标记(23 级本科 05、专科 06;24-26 级本科 5、专科 6)/ 末 2 位班内序号。
 * 23 级总长 14 位(标记占 2 位),24-26 级总长 13 位(标记占 1 位)。
 *
 * @param {string} studentNo 已通过「纯数字 + 末两位 ≤ 69」的学号
 * @param {number} [level] 用户选中的本专科(1=本科 2=专科)
 * @returns {boolean|null} true=一致, false=不一致, null=无法判定(长度/字符/标记不符/未选本专科)不拦
 */
export function matchStudentNoLevel(studentNo, level) {
  const s = (studentNo || '').trim()
  if (s.length !== 13 && s.length !== 14) return null
  if (!/^\d+$/.test(s)) return null
  const marker = s.length === 14 ? s.slice(10, 12) : s.slice(10, 11)
  let studentLevel
  if (marker === '05' || marker === '5') studentLevel = 1
  else if (marker === '06' || marker === '6') studentLevel = 2
  else return null
  if (!level) return null
  return studentLevel === Number(level)
}

/**
 * 引导总步数，进度条 aria-valuemax 用它。
 *
 * 2026-10-21 从 2 改成 3：新增了第 1 步「学校归属 + 选填学号」。
 * 放在最前面是因为它决定后续功能的可用范围
 * （校园信息已退出权限判定，2026-10-30），
 * 用户越早知道这件事，越不会在点了「加圈子」才被拦。
 */
export const TOTAL_STEPS = 3

/**
 * 兜底:后端未下发 minSelect/maxSelect 时用这两个值。
 * **只在后端字段缺失时兜底**,不覆盖后端 —— 写死就是「沉默逻辑错误」:
 * 后端把上限调到 8,前端还按 5 拦,用户会卡在选不满 5 个。
 */
export const FALLBACK_MIN_SELECT = 3
export const FALLBACK_MAX_SELECT = 12

/**
 * 设置页「管理兴趣」(复用 StepTags 的编辑态)上下限。
 * 与 `PUT /user/tags`(replaceMyTags)的存储契约对齐:openapi 该接口
 * tagIds 为 minItems: 3 / maxItems: 8 —— 这是**存储层**硬约束,
 * 不是引导首屏的「梯度裁剪」(/onboarding/tags 的 maxSelect=5)。
 * 编辑态允许到 8 个,跟后端真实能存的一致;前端只认这个契约,不另写一套。
 * 用户要求「至少保留 3 个」恰好等于存储下限,二者天然一致。
 */
export const TAG_EDIT_MIN_SELECT = 3
export const TAG_EDIT_MAX_SELECT = 8

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