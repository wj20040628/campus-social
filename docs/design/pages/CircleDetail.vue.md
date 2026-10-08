# CircleDetail.vue · 设计提示词

- **目标文件**：`frontend/src/views/CircleDetail.vue`（372 行，改造档位：**重**，1.5 人日）
- **路由**：`/circles/:id`
- **一句话目标**：把圈子详情从「480px 固定宽弹窗发活动」改成手机上「圈子头 + 内嵌发帖 + 双 Tab 内容流 + 全屏抽屉发活动」。
- **对应 Spec**：`SPEC.md §7.2`（硬编码 `el-dialog width="480px"` `:105`、两个 `el-date-picker` `:115,118`）
- **红线**：§8.1 第 2 条「禁止 `:fullscreen` 之外的固定宽弹窗」——480px 在 375px 视口必然溢出

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 组件选型 |
|---|---|---|---|
| 1 | 顶栏 | `MainLayout` 返回型：`ArrowLeft` + 圈子名（1 行截断）+ 右 `MoreFilled` | meta: `{ back: true }` |
| 2 | 圈子头 `c-banner` | 内边距 `var(--ys-space-4)`，下方 `border-bottom: 1px solid var(--ys-border)` | 见 §1.1 |
| 3 | Tab 条 | 高 44px，`position: sticky; top: var(--ys-topbar-h); z-index: var(--ys-z-sticky)`，底 `--ys-surface` | 自绘分段控件（不用 `el-tabs` 的下划线动画，它在吸顶时会抖）：「帖子」「活动」两项，各占 50%，选中态 = 文字 `--ys-accent` + 底部 2px `--ys-accent` 指示条 |
| 4 | 内嵌发帖框（仅成员可见） | 卡片 `--ys-surface` + 1px `--ys-border` + `--ys-radius-xl`，内边距 `--ys-space-4` | textarea 3 行（聚焦展开到 6 行）+ 图片预览行 + 底部操作行（左「添加图片」`Picture` 20px，右「发布到圈子」主按钮 44×44） |
| 5 | 帖子流 | 单列，条目间 `border-top: 1px solid var(--ys-border-soft)`，**无卡片盒子** | 复用 `components/PostCard.vue` |
| 6 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | — |

### 1.1 圈子头结构

```
第 1 行  圈子名 20px/600/--ys-fg（最多 2 行）              右侧「加入圈子」主按钮 44px 高
第 2 行  32px 圆形头像 + 圈主昵称 13px/--ys-muted + 「· 128 成员」
```

- 已加入时：右侧按钮换成 `el-tag`（底 `--ys-success-soft`，字 `--ys-success`），文案「已加入」，左侧加 16px `CircleCheck` 图标——**已加入状态不能只靠绿色**（色觉障碍适配）。
- 「加入圈子」按钮最小宽 88px，高 44px。

### 1.2 活动 Tab

- 顶部工具栏：「发布活动」按钮（`--ys-accent` 实底，高 44px，全宽）在成员可见时显示；非成员显示一行 13px `--ys-muted` 文字「加入圈子后就能发活动了」。
- 活动条目：单列行，行间 `border-top: 1px solid var(--ys-border-soft)`：
  ```
  第 1 行  标题 16px/500（1 行截断）                 右侧状态标签
  第 2 行  Clock 16px + 13px --ys-meta「10-25 14:00 ~ 10-25 18:00」
  第 3 行  User 16px + 13px --ys-meta「名额 12 / 20」（0 = 不限）
  ```
- 状态标签三态：招募中（`--ys-accent-soft` 底 + `--ys-accent` 字）/ 已满（`--ys-surface-2` 底 + `--ys-fg-2` 字）/ 已结束（`--ys-surface-2` 底 + `--ys-meta` 字）。**不用 `el-tag type="success"` 那套语义色**——活动状态不是成功/警告/错误。

### 1.3 「发布活动」弹窗改造（本页核心改造点）

**移动端：改为全屏抽屉**（自研 `ActionSheet` 的上层变体，或直接 `el-drawer direction="btt" size="100%"`）：

| 行 | 内容 | 规格 |
|---|---|---|
| 抽屉头 | 「发布活动」标题 + 左「取消」+ 右「发布」 | 高 56px，文字按钮各 44×44 热区 |
| 表单区 | `el-form` **`label-position="top"`** | 字段上下排布，label 13px/500/`--ys-fg-2`，字段间距 `--ys-space-5` |
| 标题 | `el-input` | 高 44px，maxlength 128 |
| 名额 | `el-input-number` | 高 44px，下方 13px `--ys-meta` hint「填 0 就是不限人数」 |
| 开始 / 结束时间 | `el-date-picker type="datetime"` | **两个 picker 各占满一行**（现有实现并排，在 375px 下必然挤压）。每个 picker 宽度 100%，输入框高 44px |
| 底部 | 固定在抽屉底部，上边框 1px `--ys-border` | 「发布」主按钮全宽 44px，`padding-bottom: calc(var(--ys-space-4) + var(--ys-safe-bottom))` |

**为什么是全屏抽屉而不是居中弹窗**：4 个字段 + 两个时间选择器在 375px 高度 667px 的屏幕上，居中弹窗要么滚动、要么溢出。全屏抽屉给足垂直空间，且底部操作始终可见。桌面端保留 `el-dialog width="480px"`（在 ≥1024px 下不溢出，符合红线）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全宽，留白 16px | `max-width: var(--ys-content-col)` 640px 居中，留白 24px |
| Tab 条吸顶 | `top: var(--ys-topbar-h)` | 同样吸顶，但桌面顶栏不遮挡时 `top: 0` |
| 活动发布 | 全屏抽屉 | 保留 `el-dialog width="480px"`，`label-position="left"`、`label-width="80px"`（现有实现） |
| 圈子头 | 名称 2 行截断 | 名称 20px 单行显示（桌面宽度够），按钮右对齐 |
| 帖子 hover | 无 | 条目底 `--ys-surface-2`，150ms `--ys-ease-out` |
| 右侧栏 | 无 | ≥1280px 时右侧 320px 栏放「圈子成员 · 近期活动」（`--ys-surface` 卡片）。**本轮若无数据则不放**——空栏位比没有更糟 |

768–1023px：与移动端同结构，留白 24px，帖子流单列。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 帖子 Tab 空：主句「这个圈子还没人说话」（`--ys-text-2xl`/600）+ 副句「发第一条，别人就有得看了」（13px `--ys-muted`）+ 主按钮「发第一条」（跳到内嵌发帖框并聚焦 textarea）。<br>活动 Tab 空：主句「还没有活动」+ 副句「凑几个人，定个时间就行」+ 主按钮「发布活动」（成员可见）/ 「加入圈子」（非成员）。<br>**注意**：现有文案「圈内还没有动态，来发第一条吧」是 `el-empty` 的默认描述，没有按钮，违反 `AC-19`，必须换掉。 |
| **加载骨架屏** | 圈子头骨架（一行标题条 + 一行头像条，高 56px）+ 3 条帖子骨架（44px 圆 + 三行文字条）。微光 `--ys-ease-linear`。 |
| **错误** | 圈子不存在 / 已解散：整页替换为居中区块——`--ys-text-2xl` 主句「这个圈子不存在，或者已经解散了」+ 13px 副句「可能链接过期了」+ 次按钮「回圈子广场」。<br>发帖失败：帖子框上方 13px `--ys-danger` 文字「没发出去，再点一次发布」，**内容保留不清空**。<br>加入圈子失败：按钮 loading 结束 + toast「没加上，网络好像断了」。 |
| **权限不足** | 非成员看帖子 Tab：正常可见（圈子是公开的）。<br>非成员点「发布活动」：按钮不渲染，改显示 13px `--ys-muted` 文字（见 §1.2）。<br>非成员点内嵌发帖框：不渲染发帖框（`v-if="canCreate"` 已有），改显示一行 13px `--ys-muted`「加入圈子后就能在圈里发帖」+ 文字按钮「加入圈子」。<br>**禁止**出现「点击后弹窗说你没有权限」这种先给按钮再拒绝的设计。 |
| **内容超长** | 圈子名最多 2 行截断；圈主昵称 1 行截断；活动标题 1 行截断；成员数 999+ 封顶；帖子正文由 `PostCard` 统一 2 行截断 + 「全文」。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| Tab 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 指示条动 `transform: translateX`，内容区只动 `opacity` |
| 抽屉升起（发活动） | `--ys-motion-slow` 280ms | `--ys-ease-in-out` | `transform: translateY(100%) → 0` |
| 抽屉遮罩淡入 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` |
| 加入圈子按钮 | loading 时 `--ys-motion-fast` 150ms | `--ys-ease-out` | 按钮内 spinner，宽度不变（防止抖动） |
| 加入成功后按钮 → 标签 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` 交叉淡入，禁止宽度动画 |
| 发帖框聚焦展开 | `--ys-motion-base` 200ms | `--ys-ease-out` | textarea `rows` 3→6 用 `max-height` 过渡，**禁止动 `height` 的 px 值** |
| 帖子 hover（桌面） | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色变化 |

- 禁止 Tab 指示条做弹性滑动（overshoot）。
- 禁止抽屉带背景缩放（iOS 那种父页面缩小效果）——与 `transform` 只动浮层的规则冲突，且在安卓低端机上掉帧。

---

## 5. 无障碍

- Tab 用 `role="tablist"` / `role="tab"` + `aria-selected`，面板 `role="tabpanel"`。
- 抽屉：焦点陷阱（打开时焦点移入首个字段，Esc 关闭，关闭后焦点归还触发按钮），`role="dialog"` + `aria-modal="true"` + `aria-labelledby`。
- 「加入圈子」/「已加入」按钮状态变化用 `aria-live="polite"` 播报一次。
- 活动状态标签必须「文字 + 颜色」双通道，且标签内文字就是状态本身（「招募中」/「已满」/「已结束」），不靠图标猜。
- 时间显示用 `--ys-font-mono` + `tabular-nums`，防跳动。
- 触控热区：Tab 项 44px、加入按钮 44px、发布按钮 44px、抽屉头文字按钮 44×44。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-success-soft  --ys-danger  --ys-warning
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-xl  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8
--ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-1  --ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer
```

**本页不使用**：`--ys-accent-2` 系（活动在本页只是圈子的一类内容，不是「搭子邀约」；活动标签走 `--ys-accent` 与中性色）、`--ys-cat-*`、`--ys-official-*`、`--ys-shadow-3`（抽屉遮罩由 `mobile-shell.md` 统一）。

---

## 7. 前端最容易做错的两点

1. **Tab 吸顶的 `top` 值**：移动端顶栏是 sticky 56px，Tab 条若写 `top: 0` 会被顶栏压住；必须 `top: var(--ys-topbar-h)`。
2. **两个 `el-date-picker` 并排**（`CircleDetail.vue:115,118`）：EP 的 datetime picker 弹出面板在 375px 下宽度约 320px，两个并排时第二个的面板会被裁切。移动端必须各占一行。

---

*本文件结束。*
