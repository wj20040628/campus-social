# Profile.vue · 设计提示词

- **目标文件**：`frontend/src/views/Profile.vue`（约 400 行，改造档位：中，0.4 人日）
- **路由**：`/user/:id`（TabBar 第 5 项「我的」指向本人 id）
- **一句话目标**：把「封面横幅 + 72px 头像 + 三统计 + 三 Tab」压成手机上紧凑的身份头 + 吸顶 Tab 内容流。
- **对应 Spec**：`SPEC.md §7.1` · `§9 AC-11/12`（认证展示只显示昵称+头像+「已认证 · XX 大学」）· `§9 AC-19`

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 本人：品牌型（MainLayout）；他人：返回型（`back: true`）+ 右 `MoreFilled`（ActionSheet：举报 / 拉黑 / 复制链接） |
| 2 | **身份头** | 内边距 `--ys-space-4`，下方 `border-bottom: 1px solid var(--ys-border)` | 见 §1.1 |
| 3 | 三统计行 | 高 56px，三等分 | 见 §1.2 |
| 4 | Tab 条 | 高 44px，吸顶 `top: var(--ys-topbar-h)` | 「动态」「粉丝」「关注」三项，选中 = `--ys-accent` 文字 + 底部 2px 指示条 |
| 5 | Tab 内容 | 单列 | 动态：复用 `PostCard`；粉丝/关注：复用 `UserList` |
| 6 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | — |

### 1.1 身份头（本页核心改造）

**删掉封面横幅**（`Profile.vue:10` 的 `.p-cover` 空 div）。理由：它在本项目里是一个纯灰色的空块，不承载任何信息，在 375px 下吃掉 80–120px 的首屏高度，把真正重要的身份信息推到折叠线以下。

```
第 1 行   56px 头像（圆形，1px --ys-border）   右侧操作区
第 2 行   昵称 18px/600/--ys-fg（1 行截断）+ 认证徽章（见下）
第 3 行   学院 · 年级 13px/--ys-muted（1 行截断）
第 4 行   兴趣标签 chips（--ys-accent-soft 底 + --ys-accent 字，最多 4 个 + 「+2」）
```

- 认证徽章（`AC-12`）：`CircleCheck` 14px + 文字「已认证 · XX 大学」，色 `--ys-success`，13px。**必须不显示真实姓名与学号**——现有 `maskUsername` 已做脱敏，保持。
- 未认证：徽章换成 `--ys-surface-2` 底 + `--ys-meta` 字「未认证」，本人可见时右侧带一个 13px 文字按钮「去认证」（跳 `/onboarding`）。
- 右侧操作区（三态）：
  - 本人：「编辑资料」次按钮（44px 高）
  - 他人已关注：「已关注」次按钮 + 「发私信」次按钮（上下两行，各 44px，宽 96px）
  - 他人未关注：「关注」主按钮（`--ys-accent` 实底）+ 「发私信」次按钮
  - **禁止「关注自己」**（现有 `:22-34` 已用 `isMe` 分支处理，保持）

### 1.2 三统计行

- 三等分，每项：数字 16px/600/`--ys-fg`（`tabular-nums`）+ 标签 12px `--ys-muted`。
- 粉丝 / 关注两项可点（跳对应 Tab），热区 44×44；「动态」不可点。
- 数字 999+ 封顶。
- **不用卡片、不用分隔竖线**——三个数字用 `--ys-space-4` 的间距分组即可。

### 1.3 「我的审核记录」区块

现有 `:53-92` 在动态 Tab 顶部渲染一个 `el-collapse`。移动端保留但：
- 默认**收起**，标题行高 48px，文案「我的审核记录 · 共 N 条」；
- 展开项内文字 13px，命中词 chips `--ys-surface-2` 底 + `--ys-fg-2` 字（**不用 `type="danger"` 的红色**——命中词是给用户看的提示，不是错误，红色会让用户以为自己违规了）；
- 审核中状态的提示文字（`:87`）保留，是真实有用的文案。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全宽 16px | `max-width: var(--ys-content-col)` 640px 居中，留白 24px |
| 封面横幅 | 已删 | **恢复**：高 160px，底 `--ys-surface-2`，圆角 `--ys-radius-xl`。桌面上横幅不占首屏成本（一屏能看到全部），且它是个人主页的身份感来源 |
| 头像 | 56px | 72px（现有 `:12` 尺寸），与横幅左下角重叠 -36px |
| 操作区 | 头像右侧，竖排 | 卡片右上角，横排 |
| Tab 吸顶 | `top: var(--ys-topbar-h)` | 同样吸顶 |
| 三统计 | 56px 高 | 高 64px，数字 18px |
| 右侧栏 | 无 | ≥1280px 时右侧 320px 放「他加入的圈子」。**无数据则不渲染** |

768–1023px：与移动端同（无横幅），留白 24px，内容限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 动态空：主句「TA 还没发过东西」（`--ys-text-2xl`/600）+ 副句「关注一下，发了就能看到」+ 主按钮（他人）「关注」/（本人）「发第一条」。<br>粉丝空：主句「还没有人关注 TA」+ 副句「多发几条，就有人来了」+ 次按钮「回首页」。<br>关注空：主句「TA 还没关注任何人」+ 副句「去圈子里看看大家都在聊什么」+ 主按钮「去逛逛」。<br>**现有三条 `el-empty` 文案（`:95`）无按钮，违反 `AC-19`。** |
| **加载骨架屏** | 现有 `:5-7` 已有「资料未回来时 el-card + el-skeleton」——**形状不对**：真实形态是「56px 头像 + 三行文字 + 三统计」，骨架应该是同样的块，不是 5 行等宽文字条。改：身份头骨架（56px 圆 + 三行条 60%/40%/80%）+ 三统计骨架（三个 40×32 块）+ 3 条帖子骨架。微光 `--ys-ease-linear`。 |
| **错误** | 用户不存在 / 账号已注销：整页替换——`--ys-text-2xl` 主句「这个用户不见了」+ 副句「可能已经注销，或者链接错了」+ 次按钮「回首页」。<br>关注操作失败：按钮回到原状态（`--ys-motion-fast`）+ toast「没关注上，网络好像断了」。<br>粉丝/关注列表加载失败：列表区替换 13px `--ys-danger`「列表没拉出来」+ 主按钮「重试」。 |
| **权限不足** | 他人主页对已登录用户始终可见，无权限态。<br>唯一限制：`AC-11` 未认证用户不能发起搭话 → 本人主页/他人主页的「发私信」按钮对未认证用户**不置灰也不隐藏**，点击后 ActionSheet 说明「要先认证校内身份才能搭话」+ 主按钮「去认证」。<br>拉黑关系（`relation.type=3`）：他人主页显示一行 13px `--ys-danger`「你已经把 TA 拉黑了」+ 文字按钮「解除拉黑」。 |
| **内容超长** | 昵称 1 行截断；学院年级 1 行截断；标签最多 4 个 + 「+2」；统计数字 999+ 封顶；个人简介（若有）3 行 + 展开。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| Tab 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 指示条 `transform: translateX`，内容 `opacity` |
| 关注按钮状态切换 | `--ys-motion-base` 200ms | `--ys-ease-out` | 文字与底色交叉淡入，**宽度锁定**（「关注」→「已关注」宽度不变，防抖动） |
| 关注按钮 loading | — | — | spinner + 宽度锁定 |
| 审核记录折叠展开 | `--ys-motion-base` 200ms | `--ys-ease-out` | `grid-template-rows: 0fr → 1fr` |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |

- 禁止头像点击放大动画。
- 禁止 Tab 内容切换时的横向滑动（与浏览器返回手势冲突）。

---

## 5. 无障碍

- 三统计的可点项是 `<button>`，不可点的「动态」是 `<div>`。
- 关注按钮 `aria-pressed`；`aria-label` 含对象名「关注 张三」。
- 认证徽章不只是图标：文字「已认证 · XX 大学」本身就是完整信息（`AC-12`）。
- 头像 `alt="张三的头像"`。
- Tab `role="tablist"` / `role="tab"` + `aria-selected`。
- 触控热区：统计项 44×44、Tab 项 44px、操作按钮 44px、审核记录标题行 48px。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-success-soft  --ys-warning  --ys-warning-soft  --ys-danger  --ys-danger-soft
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-lg
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-1  --ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base
--ys-ease-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer
```

**本页不使用**：`--ys-accent-2` 系、`--ys-cat-*`（个人兴趣标签用 `--ys-accent-soft`，不用四大类配色——四大类只用于「找搭子」场景）、`--ys-official-*`、`--ys-shadow-3`（ActionSheet 由 `mobile-shell.md` 管）、`--ys-motion-slow` / `--ys-motion-page`。

---

## 7. 前端最容易做错的一点

`el-avatar` 的 `:size` 是**属性**不是 CSS（`:12` `:size="72"`），媒体查询改不动它。移动端要 56px、桌面端要 72px，必须改成：

```vue
<el-avatar :size="avatarSize" ... />
```

```js
const avatarSize = computed(() => isDesktop.value ? 72 : 56)
```

`isDesktop` 来自 `useBreakpoint()`（从 `design-tokens.json` import 值，见 `SPEC.md §10.4`）。同类问题在本项目还有 `el-aside width="220px"`、`:size="36"` 等，都是属性硬编码。

---

*本文件结束。*
