# AnnouncementDetail.vue · 设计提示词（新增页面）

- **目标文件**：`frontend/src/views/AnnouncementDetail.vue`（新增）
- **路由**：`/announcement/:id`（`meta: { requiresAuth: true, title: '公告详情', back: true, hideTabBar: true }`）
- **一句话目标**：读完即回执——进页面自动标记已读（幂等），底部一个「我知道了」按钮既是确认也是退出口。
- **对应 Spec**：`SPEC.md §5.3`（`GET /api/v1/announcement/{id}` 返回 `is_read`；`POST /api/v1/announcement/{id}/read` 幂等）· `§9 AC-09 / AC-10`

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「公告」+ 右 `Share`（分享链接，44×44） |
| 2 | 标题区 | 内边距 `--ys-space-4`，下边距 `--ys-space-4` | 见 §1.1 |
| 3 | 正文区 | 左右 `--ys-space-4` | 见 §1.2 |
| 4 | 附件区（有附件时） | 左右 `--ys-space-4`，上边距 `--ys-space-6` | 见 §1.3 |
| 5 | **吸底操作栏** | 高 56px + `padding-bottom: var(--ys-safe-bottom)`，上边框 1px `--ys-border`，底 `--ys-surface` | 见 §1.4 |
| 6 | 底部兜底 | 正文区 `padding-bottom: calc(56px + var(--ys-space-6) + var(--ys-safe-bottom))` | 防止最后一段被操作栏盖住 |

### 1.1 标题区

```
第 1 行   来源徽章（--ys-official-badge-bg 底 + --ys-official-badge-text 字，11px，--ys-radius-sm，机构名最多 6 字）
          + 分类 chip（--ys-surface-2 底 + --ys-fg-2 字，11px，如「教务」）
          + 紧急 chip（--ys-danger-soft 底 + --ys-danger 字，仅 category=5）
          + 置顶标记（16px Top + 11px --ys-meta「置顶」，仅 is_top=1）
第 2 行   标题 20px/600/--ys-fg，--ys-lh-xl 1.4，最多 3 行
第 3 行   12px --ys-meta：发布日期（tabular-nums，「2026-10-08 14:30」）+「· 阅读 128 次」
第 4 行   发布主体署名：14px --ys-fg-2「XX 大学 教务处」+ 认证标记（16px CircleCheck --ys-success）
```

- 区块之间间距 `--ys-space-3`（12px）。
- 署名行的认证标记直连 `AC-12` 的精神：官方公告必须能看到「是谁发的、是否已认证」。

### 1.2 正文区

- 字号 `--ys-text-md` 16px，行高 `--ys-lh-md` 1.6，色 `--ys-fg`。
- `white-space: pre-wrap` 保留排版换行（教务处通知常有分行条目）。
- 内容列限宽 `--ys-content-col`（640px），桌手机一致——保证中文每行 30-38 字。
- 正文内 URL 自动识链，色 `--ys-accent`，下划线。

### 1.3 附件区

- 小标题 13px/500 `--ys-fg-2`「附件（2 个）」。
- 每条：高 56px，`--ys-surface-2` 底，圆角 `--ys-radius-lg`，内边距 `--ys-space-3`：
  ```
  左   20px Paperclip（--ys-muted）
  中   文件名 14px/--ys-fg（1 行，中间省略）+ 12px --ys-meta「PDF · 240 KB」
  右   16px Download（--ys-accent）
  ```
- 整条可点下载，热区 56px。

### 1.4 吸底操作栏（本页核心）

| 元素 | 规格 |
|---|---|
| 左：已读状态文字 | 13px。未读 → 已读后文案从「标记为已读」变为「已读 · 10-08 15:22」 |
| 右：「我知道了」主按钮 | `--ys-accent` 实底 + `--ys-accent-on`，高 44px，最小宽 120px |

**已读回执的三条规则（`AC-09`）**：

1. 进入页面 300ms 后**自动**调 `POST /announcement/{id}/read`（不是等用户点按钮）。理由：官方公告的已读回执是给发布方统计用的，让用户多点一次是 pure cost。
2. **幂等**：重复进入、重复调用都返回成功且不产生重复行。前端**不做「已读过了就不再调」的本地判断**——直接调，让后端 `uk_ann_user` 兜底。前端做去重反而会出现多端不一致。
3. 失败静默重试一次，再失败则只在操作栏左侧显示 13px `--ys-meta`「已读没记上，不影响你看」。**不弹错误**——回执失败对读者零影响，报错是噪音。

「我知道了」按钮的实际行为：`router.back()`。它不是一个功能按钮，是一个**给读者一个明确的「我看完了」出口**——尤其在看完一条长通知后，用户的下一步动作是模糊的，给一个按钮比让他自己找返回箭头好。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 结构 | 单页 | 若从 `/announcement` 两栏进入，本页是右栏内容；直接访问 URL 时是单页居中 `max-width: var(--ys-content-col)` 640px |
| 操作栏 | 吸底 | **不吸底**，排在正文下方右对齐；左列选中态由 `/announcement` 维护 |
| 「我知道了」按钮 | 有 | 直接访问时有；从两栏进入时**不显示**（右栏本身就是详情） |
| 标题 | 20px | 24px（`--ys-text-2xl`） |
| 附件 | 56px 行 | 同，宽度限 480px |

768–1023px：与移动端同（吸底操作栏），留白 24px，内容限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 公告无正文（极端情况）：**不显示空态**，只隐藏正文区与附件区，标题与署名照常展示。公告的标题本身就是信息。<br>附件列表为空：整个附件区隐藏（`v-if`），不留空标题。 |
| **加载骨架屏** | 标题区骨架（两个 11px chip 块 + 两行标题条 90%/60% + 一行 meta 条 40%）+ 正文骨架（8 行，宽度 100/96/88/100/72/100/94/50%）+ 附件骨架 1 条。微光 `--ys-ease-linear`。 |
| **错误** | 公告不存在 / 已下架：整页替换——`--ys-text-2xl` 主句「这条公告已经撤下了」+ 副句「可能被发布方下架了，去列表看看别的」+ 次按钮「看全部公告」。<br>网络失败：整页替换 13px `--ys-danger`「公告没加载出来，可能是网络断了」+ 主按钮「重试」。<br>已读回执失败：见 §1.4 第 3 条，**静默**。 |
| **权限不足** | 公告详情对所有已登录用户可见。<br>未认证用户：若后端限制（架构师确认），整页替换——`Shield` 24px `--ys-muted` + `--ys-text-xl`「公告只对已认证的同学开放」+ 主按钮「去认证」。<br>**已读人数统计**（`read_count`）对所有用户可见——它是公开数据。<br>**「谁还没看」名单**：仅发布方可见，本页不显示（在 `AdminAnnouncement.vue` 里）。 |
| **内容超长** | 标题最多 3 行；正文不截断（`overflow-wrap: anywhere`）；文件名中间省略；机构名最多 6 字；阅读次数 9999+ 封顶；附件大小用 KB/MB 自适应。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 进入页面 | — | — | 300ms 后自动发已读回执（延迟是为了不与首屏渲染抢主线程，贡献 `AC-17`） |
| 已读状态变化 | `--ys-motion-base` 200ms | `--ys-ease-out` | 操作栏左侧文字交叉淡入 |
| 「我知道了」按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 附件下载点击 | — | — | 按钮变 loading，完成后恢复 |
| 骨架 → 正文 | — | — | 直接替换，**不做交叉淡入**（淡入会掩盖真实加载时间） |
| 路由返回 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | MainLayout 统一提供 |

- 禁止正文的逐段淡入。
- 禁止附件下载成功的全屏提示；按钮内 spinner 消失即可。

---

## 5. 无障碍

- 正文区 `role="article"`，`aria-labelledby` 指向标题元素。
- 新公告到达（`AC-08` 豁免免打扰）时用 `role="status"` 播报——但本页是详情页，播报发生在首页/通知栏，本页不需要重复。
- 附件条目 `aria-label` 含完整信息「下载附件：2026 春季选课通知.pdf，PDF，240 KB」。
- 置顶与紧急都有文字，不只靠图标与颜色。
- 「我知道了」按钮 `aria-label="我知道了，返回公告列表"`。
- 触控热区：附件条目 56px、按钮 44px、顶栏图标 44×44。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on
--ys-danger  --ys-danger-soft  --ys-success
--ys-official-badge-bg  --ys-official-badge-text
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-xl  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-lh-xl  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-6  --ys-space-8
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-instant  --ys-motion-base  --ys-motion-page
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-official-surface`（详情页正文在 `--ys-bg` 上，不用官方浅底——浅底是列表里区分条目用的）、`--ys-accent-soft`（无浅底提示条）、`--ys-accent-2` 系、`--ys-cat-*`、`--ys-warning`（无警告态）、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-fast` / `--ys-motion-slow`（本页无 hover 态与抽屉）。

---

## 7. 前端最容易做错的一点

**已读回执的前端去重**。看到 `AC-09` 写「幂等」，很容易顺手在前端加一句 `if (!ann.is_read) postRead()`。这会出问题：用户在手机上读了、后端写成功但前端 `is_read` 缓存未更新，下次进入前端以为没读过就不发，导致发布方的「谁还没看」名单里永远有这个人（`AC-10` 直接失效）。

正确做法：**无条件调用**，幂等交给后端 `uk_ann_user` 保证。前端只负责不因为失败而报错。

---

*本文件结束。*
