# AdminReview.vue · 设计提示词

- **目标文件**：`frontend/src/views/AdminReview.vue`（约 300 行，改造档位：中，0.4 人日 —— **但这是全项目交互难度最高的一页**）
- **路由**：`/admin/review`（`meta: { requiresAuth: true, requiresAdmin: true, back: true, hideTabBar: true }`）
- **一句话目标**：让管理员在**手机上一手握持**完成内容审核与举报处置，主操作吸底、不进二级菜单、误触可逆。
- **对应 Spec**：`SPEC.md §0 OD-19`（管理员在手机上审）· `§5.1`（`/admin/reports` 三端点）· `§9 AC-13 / AC-14 / AC-20 / AC-04~06 相关`
- **硬约束（OD-19 原文推导）**：「后台页保留桌面形态」这条路已死。举报处置有时效要求，管理员不在手机上响应会导致处置延迟。

---

## 0. 本页的两个内容域（Spec 未明说，本设计补齐）

`SPEC.md §5.1` 把举报端点写在 G1 段落下，但 `§9 AC-14` 要求「管理员在手机视口打开**审核页**可完成举报处置」。现有 `AdminReview.vue` 处理的是 `review_task`（敏感词机审队列）。故本页设计为**两个 Tab**：

| Tab | 数据源 | 处置动作 | 归属 |
|---|---|---|---|
| 内容审核 | `review_task`（现有 `/api/review/*`） | 通过 / 驳回（填理由） | 现有功能 |
| 举报处置 | `report`（`SPEC.md §5.1`：`GET /admin/reports`、`POST /admin/reports/{id}/handle`） | 成立并封禁 / 驳回 / 忽略 | F4 上线门槛项 |

**未读计数优先显示举报**：Tab 标签写「举报 (3)」「审核 (12)」，默认落在**举报** Tab——因为 OD-19 的时效压力来自举报，不来自机审队列。

---

## 1. 移动端布局（<640px，默认基准）

### 1.1 逐条处置模式（移动端默认，单手握持）

垂直结构逐行：

| 行 | 区块 | 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 标题「审核处置」+ 右 `List`（切换到列表模式，见 §1.2） |
| 2 | 进度条 | 高 4px，底 `--ys-surface-2` | 已完成 / 总数，填充色 `--ys-accent`。下方一行 12px `--ys-meta`「第 3 条 / 共 12 条待处置」 |
| 3 | 可滚动内容区 | `flex: 1` | 见 §1.3 卡片结构。**只有这一区滚动** |
| 4 | **吸底操作栏** | 高 56px + `padding-bottom: var(--ys-safe-bottom)`，`border-top: 1px solid var(--ys-border)`，底 `--ys-surface` | 见 §1.4。**固定在屏幕底部，不随内容滚动** |

### 1.2 列表模式（顶栏 `List` 图标切换）

同 TabBar 无关的单列列表，每条是 §1.3 卡片的**折叠版**（只显示：类型标签 + 被举报对象摘要 2 行 + 举报时间），点击进全屏处置页。**列表模式只用于回看与检索，不用于批量处置**——批量处置在手机上必然误触。

### 1.3 处置卡片结构（内容区，可滚动）

```
[举报域]
第 1 行   原因类型标签（如「骚扰」）                     右侧 12px --ys-meta 举报时间
第 2 行   举报人：36px 头像 + 昵称 13px/--ys-muted（脱敏）+「· 计算机学院」
第 3 行   分隔线 1px --ys-border-soft
第 4 行   「被举报内容」小标题 13px/500/--ys-fg-2
第 5 行   内容正文 16px/--ys-fg，整段保留换行，最多 8 行 + 「展开全文」
第 6 行   媒体缩略（若有）：一行 3 张 80×80，--ys-radius-lg
第 7 行   命中词区（若有）：--ys-danger-soft 底，13px，chips 为命中词
第 8 行   补充说明（举报人填的 reason_text）：--ys-surface-2 底，14px，圆角 --ys-radius-lg

[内容审核域]
第 1 行   作者：36px 头像 + 昵称 14px/500 + 12px --ys-meta 时间
第 2 行   内容正文 16px，同上
第 3 行   命中词区（同上）
第 4 行   AI 机审预判：--ys-info-soft 底，13px「AI 机审：建议驳回」+ 理由
```

- 卡片整体**不套 `el-card`**（避免卡片盒子 + 阴影），用 `--ys-surface` 底 + 1px `--ys-border` + `--ys-radius-xl`，内边距 `--ys-space-5`。
- 区块间距 `var(--ys-space-5)` = 20px。

### 1.4 吸底操作栏（本页核心）

**举报域三动作**（一行三按钮，等宽，`gap: var(--ys-space-2)`，高 44px）：

| 按钮 | 视觉 | 语义 |
|---|---|---|
| 忽略 | 次按钮：透明底 + 1px `--ys-border-control` + `--ys-fg` | 不采纳，不改内容状态 |
| 驳回 | 次按钮：透明底 + 1px `--ys-border-control` + `--ys-fg` | 举报不成立，记录驳回 |
| 成立并封禁 | **危险按钮**：`--ys-danger` 实底 + `--ys-accent-on` 白字 | 举报成立，封禁被举报对象 |

**内容审核域两动作**：通过（`--ys-success` 实底 + 白字）/ 驳回（`--ys-danger` 描边 + `--ys-danger` 文字）。驳回需要理由 → 点「驳回」先展开一个 32px 高的理由输入框（在按钮行**上方**，不是弹窗），再点「确认驳回」。

**三条硬规则**：

1. **操作不进二级菜单**。现有实现（`:56-72`）的「驳回」要先点一次展开输入框、再点「确认驳回」，且「通过」前面还有 `ElMessageBox.confirm` —— 手机上这等于三步。本设计改为：
   - 「通过」/「忽略」：**一次点击即生效**，随后 5 秒撤销窗口（见 §4）。
   - 「成立并封禁」/「驳回」：因为不可逆或需理由，走 `ActionSheet` 二次确认（见 `../mobile-shell.md`），**但仍然只有一次点击 + 一次确认**，不是三层。
2. **危险动作在右、安全动作在左**。右手握持时拇指自然落在屏幕右下，`--ys-danger` 放最右是反的——**所以「成立并封禁」放最右是刻意的让误触更难**；而高频的「驳回」放中间。三者都是 44px 高、间距 8px，误触相邻按钮的概率被间距吃掉。
3. **误触可逆**。任何处置成功后，底部弹出一个 Toast 条（不是 `ElMessage` 顶部 toast——顶部在手机上够不着），含「已封禁 XXX」+ 右侧「撤销」文字按钮，**5 秒可点**，5 秒后自动淡出并真正落库。撤销期间后端不落库（`handle` 请求延迟 5 秒发，或先发 `status=1` 再用「改判」覆盖——**具体实现由架构师定，设计只要求用户在 5 秒内能反悔**）。已处理 Tab 内的每条右侧提供「改判」文字按钮，可重新处置。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 结构 | 逐条处置 + 吸底操作栏 | **双栏**：左 320px 待处置列表（可滚动、可键盘上下键切换）+ 右详情区。**默认进列表模式**，不进逐条模式 |
| 操作栏 | 吸底固定 | 移到右侧详情区底部（不吸底，随内容排布），按钮右对齐、不等宽（按文案宽度） |
| 二次确认 | ActionSheet | `ElMessageBox.confirm` 居中弹窗（桌面保留现有交互） |
| 撤销 Toast | 底部 Toast 条 | 右上角 `ElMessage` +「撤销」按钮（桌面鼠标够得着顶部） |
| 键盘 | 无 | `J`/`K` 上下切换、`A` 通过、`R` 驳回、`U` 撤销。**桌面管理员是高频用户，快捷键是刚需**（角色测试「亚历克斯·急躁高手」） |
| 容器 | 全宽 | `max-width: var(--ys-container-max)` 1180px 居中 |

768–1023px：与移动端同（逐条处置 + 吸底操作栏），但卡片限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 举报 Tab 空：主句「没有待处理的举报」（`--ys-text-2xl`/600）+ 副句「校园里这会儿挺安静，这是好事」（13px `--ys-muted`）+ 次按钮「看看已处理的」。<br>审核 Tab 空：主句「机审队列是空的」+ 副句「有命中敏感词的内容会自动进这里」+ 次按钮「看已处理」。<br>**现有文案「没有待审内容」/「暂无记录」是 `el-empty` 默认描述且无按钮，违反 `AC-19`，必须换。** |
| **加载骨架屏** | 逐条模式：卡片骨架（一行标签条 + 两行作者条 + 六行正文条），微光 `--ys-ease-linear`。列表模式：6 条折叠条目骨架。**禁止全屏 spinner**——管理员是等待成本最高的一类用户，骨架屏让他知道「在加载」而不是「卡死了」。 |
| **错误** | 单条处置失败：操作栏上方浮出一行 `--ys-danger-soft` 底 + `--ys-danger` 文字「没处置成功，再点一次」，操作栏按钮恢复可点（loading 结束），**当前卡片不跳走**。<br>列表加载失败：整页替换「没加载出来，点这里重试」+ 重试按钮。<br>网络中断时已点出的处置：进入「待重发」队列，Toast 提示「网络断了，恢复后会自动提交」——管理员处置不能因为一次断网就丢。 |
| **权限不足** | `isAdmin === false` 时：整页替换为居中区块——`Shield` 自绘图标 24px（`--ys-muted`）+ 主句「这个页面只有管理员能看」+ 副句「如果你应该是管理员，找负责人开一下权限」+ 次按钮「回首页」。<br>API 返回 403 `NOT_ADMIN`（`AC-20`）时同上，不弹 toast 了事。<br>token 过期：由 `request.js` 统一处理跳登录，本页不重复实现。 |
| **内容超长** | 正文最多 8 行 + 「展开全文」（展开后内容区可滚动，操作栏不动）；昵称 1 行截断；命中词 chips 超过 6 个显示「+3」；举报理由 3 行截断 + 展开；时间用 `--ys-font-mono` + `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 处置成功 → 下一条 | `--ys-motion-base` 200ms | `--ys-ease-in-out` | 当前卡片 `translateX(-100%)` + `opacity` 淡出，下一条从右侧 12px 淡入。**禁止**弹跳或回弹 |
| 撤销 Toast 进入 | `--ys-motion-base` 200ms | `--ys-ease-out` | 从底部 `--ys-safe-bottom` 之上 8px 滑入 8px，只动 `transform` + `opacity` |
| 撤销 Toast 停留 | 5000ms | — | 倒计时用文字「5 秒后可撤销」改为进度条更直观：Toast 底边一条 `--ys-accent` 进度条 `linear` 收缩 |
| 撤销 Toast 淡出 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` → 0，同时真正提交 |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7`（危险按钮用 `--ys-danger` 的 active 态） |
| ActionSheet 升起 | `--ys-motion-slow` 280ms | `--ys-ease-in-out` | 见 `../mobile-shell.md` |
| 加载态按钮 | — | — | spinner + 按钮宽度锁定（防抖动） |

- 禁止处置成功时的「对勾放大回弹」庆祝动画。管理员一天点几百次，庆祝动画是负担。
- 禁止卡片切换时的纵向位移（会造成列表跳变错觉）。

---

## 5. 无障碍

- 操作栏按钮全部是 `<button>`，不接受 `div @click`。
- 危险按钮不能只靠红色区分：按钮**文字就是动作本身**（「成立并封禁」），且 ActionSheet 二次确认里再写一次完整后果说明「封禁后该用户将不能发帖和发起搭话」。
- 撤销 Toast `role="status"` + `aria-live="assertive"`（这是需要立刻听到的信息）。
- 卡片内容区 `tabindex="-1"`，切换条目时程序化聚焦，让屏幕阅读器读出新条目。
- 触控热区：操作栏按钮 44px 高、间距 8px；顶栏图标 44×44；卡片内「展开全文」44×44。
- 对比度：`--ys-danger` `#C32B20` 白字 5.70，达标；`--ys-success` `#157347` 白字 5.87，达标。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-success-soft  --ys-warning  --ys-warning-soft
--ys-danger  --ys-danger-soft  --ys-info  --ys-info-soft
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm
--ys-weight-regular  --ys-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6
--ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-container-max  --ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer  --ys-z-toast
```

**本页不使用**：`--ys-accent-2` 系（搭子专属）、`--ys-cat-*`（兴趣标签不出现在审核上下文）、`--ys-official-*`（官方内容不进审核队列）、`--ys-shadow-3`（Toast/ActionSheet 由 `mobile-shell.md` 管）。

---

## 7. 与后端对齐的一点（需架构师确认，非设计决定）

「5 秒撤销窗口」需要后端配合，两种实现任选其一：

- **A（推荐）**：前端延迟 5 秒再发 `/admin/reports/{id}/handle`。优点零后端改动；缺点：管理员杀掉页面则处置丢失。
- **B**：立即发 `handle`（`status=1`），撤销时再发一次 `handle`（`status=2` 或「改判」）。优点不丢失；缺点产生两条审计日志。

设计侧不关心选哪个，只要求：**用户在 5 秒内点了撤销，结果必须与没点过一致**。请架构师在 Phase 2 的 `openapi.yaml` 里定死，前端据此实现。

---

*本文件结束。*
