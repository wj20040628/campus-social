# AdminAnnouncement.vue · 设计提示词（新增页面）

- **目标文件**：`frontend/src/views/AdminAnnouncement.vue`（新增）
- **路由**：`/admin/announcement`（`meta: { requiresAuth: true, requiresAdmin: true, title: '公告管理', back: true, hideTabBar: true }`）
- **一句话目标**：管理员在**手机上**能发公告、置顶、上下架，并看到「谁还没看」名单且一键提醒。
- **对应 Spec**：`SPEC.md §5.3`（admin 三端点）· `§7.3` 新增页 · `§0 OD-19`（后台页必须手机可用）· `§9 AC-07 / AC-10 / AC-20`
- **公告分类**：`1教务 2后勤 3活动 4社团 5紧急 6其他`（`SPEC.md §6.1`）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「公告管理」+ 右 `Plus`（新建公告，打开全屏编辑器） |
| 2 | 状态筛选条 | 高 44px，下边距 `--ys-space-4` | 4 个 chip：全部 / 已发布 / 草稿 / 已下架。形态与 `Announcement.vue` 的分类 chip 一致（选中 = `--ys-accent` 底 + 白字） |
| 3 | 公告管理列表 | 单列，条目间 `border-top: 1px solid var(--ys-border-soft)` | 见 §1.1 |
| 4 | 底部兜底 | `padding-bottom: calc(var(--ys-space-6) + var(--ys-safe-bottom))` | — |

### 1.1 管理条目结构（比前台条目多一层操作）

```
第 1 行   状态 chip（11px：已发布 --ys-success-soft/--ys-success；草稿 --ys-surface-2/--ys-fg-2；已下架 --ys-surface-2/--ys-meta）
          + 分类 chip（11px，--ys-surface-2 底 + --ys-fg-2 字）
          + 置顶标记（16px Top + 11px --ys-accent「置顶」）
第 2 行   标题 15px/500/--ys-fg，最多 2 行
第 3 行   12px --ys-meta：发布时间 + 「· 已读 128 / 已发 1042」
第 4 行   操作行（高 44px，上边距 --ys-space-2）：
          文字按钮横排，间距 --ys-space-4，各 44×44 热区：
          「置顶 / 取消置顶」「上架 / 下架」「编辑」「谁还没看」
```

- 条目内边距 `var(--ys-space-4)`。
- 操作行**不折叠进「更多」菜单**（OD-19 的硬要求：操作不藏进二级菜单）。4 个文字按钮在 375px 下横向排列宽度足够（每个约 60-72px + 间距 16px ≈ 300px）。
- 「谁还没看」按钮仅在 `status=1`（已发布）时渲染。

### 1.2 「谁还没看」面板（AC-10，本页核心）

点击后从底部升起 **ActionSheet**（见 `../mobile-shell.md`），高度 70vh：

```
头部 56px：标题「还有 914 人没看」+ 右侧「一键提醒」主按钮（44px 高）
          + 右上角 Close（44×44）
搜索行 44px：el-input「按学院 / 昵称搜」，左右 --ys-space-4
名单列表：单列，每行 56px
          左 36px 头像 + 中 昵称 14px/500 + 学院 12px --ys-meta
          右 文字按钮「提醒」（44×44，--ys-accent 字）
底部：padding-bottom: var(--ys-safe-bottom)
```

- 名单只显示**前 50 人**（后端若返回更多，前端分页加载）。头部写「还有 914 人没看」而不是「914 人没看」——管理员关心的是绝对数字，但这个面板装不下 914 条。
- 「一键提醒」点击后：头部按钮变 loading，完成后 Toast「已提醒 914 人」。**不做二次确认**——提醒是弱打扰，且这是管理员的高频动作。
- 单人「提醒」点击后：该行右侧按钮变「已提醒」（`--ys-meta` 字，禁用态），`--ys-motion-fast` 过渡。
- 面板内不做批量勾选（手机上的批量选择是误触之源）。

### 1.3 新建 / 编辑公告（全屏编辑器）

顶栏 `Plus` 或条目「编辑」打开**全屏页面**（不是抽屉——公告有标题+正文+分类+置顶，字段多，抽屉装不下）：

```
顶栏 56px：左「取消」右「发布」，中「新建公告」
第 1 区   标题：label「标题」13px/500 + el-input 高 44px，maxlength 64
第 2 区   分类：label「分类」+ 6 个 chip 单选（教务/后勤/活动/社团/紧急/其他），
          形态同筛选 chip，选中 = --ys-accent 底 + 白字
第 3 区   正文：label「正文」+ el-textarea，min-height 240px，--ys-text-md 16px
第 4 区   置顶开关：一行 56px，左「置顶这条公告」14px，右 Switch（EP el-switch）
          下方 13px --ys-meta「置顶最多同时 3 条，超过时先取消一条」（hint 常显，不是报错后才出现）
底部操作栏 56px + 安全区：左「存草稿」次按钮，右「发布」主按钮，各 44px 等宽
```

- 字段间距 `--ys-space-5`；分区之间 1px `--ys-border-soft` + `--ys-space-5` 内边距。
- 编辑器页面 `meta: { back: false, hideTabBar: true }`，退出只能走顶栏「取消」——防止误触返回丢失内容。取消时若已填内容，弹 ActionSheet 二次确认「内容还没发，确定丢掉？」（两个选项：丢掉 / 继续编辑）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 结构 | 单列列表 + ActionSheet | **双栏**：左 400px 列表 + 右侧编辑/预览区。点「编辑」直接在右栏内联编辑，不跳页 |
| 「谁还没看」 | ActionSheet 70vh | 右侧面板（`--ys-surface` + 1px `--ys-border` + `--ys-radius-xl`），宽度 480px，可勾选批量提醒 |
| 新建公告 | 全屏页面 | 右栏内联表单，字段两列排布（标题+分类一行，正文整行） |
| 操作行 | 4 个文字按钮横排 | 条目 hover 时右侧浮现图标按钮（置顶 `Top` / 下架 `Download` 方向语义不合适，改 `Hide` / 编辑 `EditPen` / 名单 `View`），各带 `el-tooltip` |
| 容器 | 全宽 16px | `max-width: var(--ys-container-max)` 1180px 居中，留白 24px |
| hover | 无 | 条目底 `--ys-surface-2` |

768–1023px：与移动端同（单列 + ActionSheet），留白 24px，内容限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 1) 全空：主句「还没有发过公告」（`--ys-text-2xl`/600）+ 副句「学校有通知要发？在这里发一条，全校同学都能看到」+ 主按钮「新建公告」。<br>2) 某状态筛选下为空：主句「这个状态下没有公告」+ 副句「换一个筛选看看」+ 主按钮「看全部」。<br>3) 「谁还没看」为空（全部已读）：面板标题行改文案「全部都看过了」，下方一行 13px `--ys-meta`「这条公告所有人都读过了」，**不出空态图片**。 |
| **加载骨架屏** | 列表：6 条管理条目骨架（两个 chip 块 + 两行标题条 + 一行 meta 条 + 一行操作按钮块）。编辑器不需要骨架（静态表单）。「谁还没看」面板：8 条名单骨架（36px 圆 + 两行条）。微光 `--ys-ease-linear`。 |
| **错误** | 单条操作失败（置顶/下架/提醒）：该条目操作行上方浮出 13px `--ys-danger`「没成功，再点一次」，按钮恢复可点。<br>置超额（>3 条）：操作行上方 13px `--ys-warning`「置顶位满了，先取消一条」+ 该按钮临时禁用。<br>发布失败：编辑器底部操作栏上方 13px `--ys-danger`「没发出去，再点一次」，**标题与正文全部保留**。<br>名单加载失败：面板内替换 13px `--ys-danger`「名单没拉出来」+ 主按钮「重试」。 |
| **权限不足** | `isAdmin === false` 或 API 403 `NOT_ADMIN`（`AC-20`）：整页替换——自绘 `Shield` 24px `--ys-muted` + `--ys-text-xl` 主句「这个页面只有管理员能看」+ 13px 副句「如果你应该是管理员，找负责人开一下权限」+ 次按钮「回首页」。<br>与 `AdminReview.vue` / `AdminAudit.vue` **完全一致**（三页共用一个权限不足区块，抽成组件）。 |
| **内容超长** | 标题最多 2 行（列表）/ 64 字硬上限（编辑器）；正文不截断（`overflow-wrap: anywhere`）；机构名最多 6 字；已读/已发数 9999+ 封顶；名单昵称 1 行截断；时间 `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| ActionSheet 升起（谁还没看） | `--ys-motion-slow` 280ms | `--ys-ease-in-out` | `transform: translateY(100%) → 0` |
| 遮罩淡入 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` |
| 状态 chip 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色与文字色 |
| 操作按钮 loading | — | — | spinner + **宽度锁定** |
| 操作成功后条目状态变化 | `--ys-motion-base` 200ms | `--ys-ease-out` | chip 文字与底色交叉淡入 |
| 单人「提醒」→「已提醒」 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 文字色 → `--ys-meta` + 禁用 |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |

- 禁止发布成功的全屏庆祝。Toast「公告发出去了，全校同学会看到」。
- 禁止「谁还没看」面板的名单做入场动画（914 人名单逐条淡入是性能灾难）。

---

## 5. 无障碍

- 操作行 4 个按钮都是 `<button>`，`aria-label` 含公告标题（「置顶公告：关于选课的通知」）。
- 状态 chip 的文字就是状态本身（「已发布」「草稿」「已下架」），颜色只辅助。
- 置顶传达三重：`Top` 图标 + 「置顶」文字 + `--ys-accent` 色。
- ActionSheet 焦点陷阱 + Esc 关闭 + 关闭后焦点归还触发按钮（见 `../mobile-shell.md`）。
- 「一键提醒」按钮 `aria-describedby` 关联到人数说明。
- 触控热区：操作按钮 44×44、名单行 56px、chip 44×44、面板头按钮 44px。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-success-soft  --ys-warning  --ys-warning-soft  --ys-danger  --ys-danger-soft
--ys-official-badge-bg  --ys-official-badge-text
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-xl  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-container-max  --ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer  --ys-z-toast
```

**本页不使用**：`--ys-accent-2` 系（公告不是搭子邀约）、`--ys-cat-*`（公告分类用中性 chip）、`--ys-official-surface`（管理列表条目用 `--ys-surface`，不与前台官方条目混淆）、`--ys-shadow-1` / `--ys-shadow-3`（遮罩由 `mobile-shell.md` 管）、`--ys-motion-page`（编辑器是全屏页面但走路由过渡，由 MainLayout 提供）。

---

## 7. 一个需要架构师确认的端点缺口

`AC-10` 要求「对未读人群支持**一键提醒**」，但 `SPEC.md §5.3` 的端点清单里只有：

```
POST   /api/v1/admin/announcement
PATCH  /api/v1/admin/announcement/{id}
GET    /api/v1/admin/announcement?status=&cursor=
```

**缺少两个端点**：

- `GET /api/v1/admin/announcement/{id}/unread-users?cursor=` —— 「谁还没看」名单
- `POST /api/v1/admin/announcement/{id}/remind` —— 一键提醒（单人提醒复用同一端点，body 带 `userIds?`）

设计侧按这两个端点存在来设计（见 §1.2）。请架构师在 `docs/api/openapi.yaml` 补上并在 Phase 2 回填确认；若决定本轮不做，则 §1.2 整个面板从 MVP 移除，管理条目操作行减为 3 个按钮。

---

*本文件结束。*
