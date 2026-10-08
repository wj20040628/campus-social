# MainLayout.vue · 设计提示词

- **目标文件**：`frontend/src/layouts/MainLayout.vue`（229 行，改造档位：**重**，1.5 人日）
- **路由**：无自身路由，是 `router/index.js` 中 `/` 下全部子路由的父布局
- **一句话目标**：提供一套「手机 = 顶栏 56px + 底部 TabBar 56px + 内容区」/「桌面 = 左侧 220px Sidebar + 顶栏 + 内容区」的双外壳，同一组件内按断点切换，让其余 15 个页面只关心自己的内容区。
- **对应 Spec**：`SPEC.md §7.1`（重档）· `§7.2`（结构性改造三页之一）· `§7.4`（自研移动端组件）· `§8`（Token）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行（从上到下）：

| 行 | 区块 | 高度 / 间距 | 组件选型 | 说明 |
|---|---|---|---|---|
| 1 | 顶栏 `TopBar` | 固定 `var(--ys-topbar-h)` = 56px，`position: sticky; top: 0; z-index: var(--ys-z-sticky)` | 原生 `<header>` + `<nav>`，不用 `el-header` | **保留 56px sticky**（现有实现已有，高度就是移动端标准值，换掉没有收益）。底边 1px `var(--ys-border)`，吸顶后加 `var(--ys-shadow-2)` |
| 1a | 顶栏-左 | 图标 24px，热区 44×44 | 两种模式，按路由 meta 切换（见 §1.1） | 品牌型：品牌字「友声」`--ys-text-lg`/600/`--ys-fg`；返回型：`ArrowLeft` 图标 + 页面标题 |
| 1b | 顶栏-右 | 两个图标按钮，各 44×44，间距 `var(--ys-space-1)` | `Search`（跳 `/search`）、`Bell`（跳 `/notify`，带未读角标） | 现有实现中间的圆形「+」发布按钮**在移动端删除**——TabBar 中位已有发布入口，两个发布入口是冗余；且它带 `box-shadow: 0 4px 12px rgba(64,158,255,0.35)` 属彩色发光，命中红线 3 |
| 2 | 内容区 `<main>` | `flex: 1`，左右留白 `var(--ys-gutter-phone)` 16px，顶部 `var(--ys-space-4)`，底部 `calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | `<router-view />` | 见 §1.2 的 TabBar 隐藏规则 |
| 3 | 底部 TabBar | 56px + `env(safe-area-inset-bottom, 0px)` | 自研 `components/mobile/TabBar.vue`（详见 `../mobile-shell.md`） | 5 个入口：首页 / 圈子 / 发布 / 消息 / 我的 |

### 1.1 顶栏两种模式（路由 meta 驱动）

```js
// router/index.js 的 meta 增加两个字段（新增页面的 meta 也照此写）
meta: { title: '帖子详情', back: true, hideTabBar: true }
```

| meta | 顶栏形态 | 用于 |
|---|---|---|
| 无 `back` | 品牌型：左「友声」+ 右 搜索/铃铛 | 首页、圈子列表、消息列表、我的 |
| `back: true` | 返回型：左 `ArrowLeft` + 标题（1 行截断）+ 右 搜索/铃铛 | 帖子详情、圈子详情、公告详情、发布页、设置 |
| `hideTabBar: true` | 隐藏底部 TabBar，内容区底部改 `calc(var(--ys-space-4) + var(--ys-safe-bottom))` | 会话页（`/chat` 打开会话后）、帖子详情、发布页、编辑器类抽屉 |

**为什么是 meta 而不是组件内自己算**：现有 `activeMenu` 计算函数已经是一串 `startsWith` 判断（`MainLayout.vue:60-72`），再加两种模式会变成三处互相打架的分支。meta 是 vue-router 原生机制，一处声明、全局生效。

### 1.2 内容区底部留白的三种取值

```css
.content { padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom)); }  /* 默认 */
.content.no-tabbar { padding-bottom: calc(var(--ys-space-4) + var(--ys-safe-bottom)); }
.content.immersive { padding-bottom: var(--ys-safe-bottom); }  /* 会话页，输入区自己撑底 */
```

### 1.3 分区节奏

- 顶栏与内容区之间：`0`（顶栏已有 1px 下边框承担分隔）。
- 内容区内部区块间距：`var(--ys-space-4)` = 16px 手机 / 由各页自定。
- 相邻可点元素间距 ≥ `var(--ys-space-2)` = 8px。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端差异 |
|---|---|---|
| 导航 | 底部 TabBar | **隐藏 TabBar**，改左侧固定 Sidebar `var(--ys-sidebar-w)` = 220px，沿用现有 `el-menu` 但去掉 `el-aside width="220px"` 硬编码，改 CSS `width: var(--ys-sidebar-w)` |
| 顶栏 | 品牌字 / 返回箭头 | 固定在右侧列顶部，高 56px，左端为当前页标题（`--ys-text-xl`/600），右端搜索框（宽 240px）+ 头像下拉。**不再显示品牌字**（品牌字在侧栏顶部） |
| 内容区 | 左右留白 16px | 左右留白 `var(--ys-gutter-desktop)` 24px；内容列 max-width `var(--ys-content-col)` 640px 居中；≥1280px 时容器锁 `var(--ys-container-max)` 1180px |
| 底部留白 | TabBar 高度 + 安全区 | 改 `var(--ys-space-12)` = 48px |
| 顶栏发布按钮 | 已删 | **保留**但不放中间：移到右端搜索框左侧，40×40 圆形，阴影改 `var(--ys-shadow-2)`（去掉彩色发光） |
| 断点 | 基础样式 | `@media (min-width: 1024px)`（或 `--ys-bp-lg`） |

768–1023px（平板）：**TabBar 仍在底部**，顶栏用品牌型，内容区留白 24px，不出现 Sidebar。与手机的唯一区别是留白与内容列宽度。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 布局组件本身无数据。但 TabBar 的「消息」入口在未读为 0 时角标必须**完全不渲染**（不是渲染一个 0），避免无意义红点 |
| **加载骨架屏** | 首屏 `userStore.userInfo` 未就绪时，顶栏右侧头像位显示 34px 圆形骨架（`--ys-surface-2` 底 + `linear` 微光），**不要**整页骨架——布局骨架会阻塞所有子页，得不偿失 |
| **错误** | 顶栏头像拉取失败：头像位降为首字母圆形块（`--ys-surface-2` 底 + `--ys-fg-2` 文字），不报错、不弹窗。全站级错误（如 WS 断开）由各页自行处理，布局不接管 |
| **权限不足** | 管理员菜单项（内容审核 / 操作日志 / 公告管理）在 `userStore.isAdmin === false` 时**不渲染**。管理员页被直接访问时由 `router/index.js:41-45` 的守卫拦截并提示，本页只需保证菜单不出现 |
| **内容超长** | 顶栏标题 1 行截断（`text-overflow: ellipsis`）；侧栏菜单项文字 1 行截断；未读角标 99+ 封顶（TabBar 与铃铛都如此） |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 顶栏吸顶阴影出现 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 只动 `box-shadow`，不动 `height` |
| TabBar 选中态切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 图标 `currentColor` 变色 + 文字色变，禁止缩放弹跳 |
| 顶栏图标按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7`，禁止 `transform: scale()` |
| 路由切换（内容区） | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 只做 `opacity` 渐入 + 8px `translateY`，禁止横向滑动翻页（与浏览器手势冲突） |
| 侧栏 hover（桌面） | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色 `--ys-surface-2` |

- 禁止给顶栏加滚动隐藏（scroll-hide）：会让内容区高度跳变，产生 CLS。
- 禁止 TabBar 图标做「点击放大回弹」。

---

## 5. 无障碍

- 顶栏用 `<header>`，TabBar 用 `<nav aria-label="主导航">`，侧栏用 `<nav aria-label="侧边导航">`，内容区用 `<main>`。
- TabBar 每项 `role="tab"` + `aria-selected`，选中项文字色 `--ys-accent`（对比 5.36）。
- 铃铛按钮 `aria-label="通知，3 条未读"`（数字随实际值变化，0 时不加此后缀）；搜索按钮 `aria-label="搜索"`。
- TabBar 与侧栏同一时刻**只有一个在 DOM 中可聚焦**——移动端隐藏侧栏用 `display: none` 而非 `visibility: hidden`，否则 Tab 键会走到不可见元素。
- 焦点环统一 `var(--ys-focus-ring)`；禁止 `outline: none`。
- Skip link：内容区前加一个视觉隐藏、聚焦时可见的「跳到主内容」链接（仅键盘用户可达）。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-soft  --ys-accent-on
--ys-danger（未读角标）
--ys-text-sm  --ys-text-base  --ys-text-lg  --ys-text-xl
--ys-lh-base  --ys-ls-base  --ys-weight-medium  --ys-weight-semibold
--ys-font-body
--ys-space-1  --ys-space-2  --ys-space-4  --ys-space-12
--ys-radius-lg  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-page
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-topbar-h  --ys-tabbar-h  --ys-sidebar-w  --ys-aside-w  --ys-content-col  --ys-container-max
--ys-gutter-phone  --ys-gutter-desktop
--ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（辅色只给活动/搭子）、`--ys-shadow-3`（浮层级，布局不是浮层）、`--ys-cat-*`（兴趣标签不出在外壳）。

---

## 7. 前端最容易做错的三点

1. **`min-height: 100vh`**（`MainLayout.vue:139,142`）在移动端会被地址栏遮挡 → 改 `100dvh`（`vh` 保留作降级）。
2. **`el-aside width="220px"`**（`:4`）是属性硬编码，媒体查询改不掉 → 改成 CSS `width` + 断点。
3. **TabBar 与内容区底部留白重复叠加**：TabBar 用 `position: fixed` 时，内容区必须自己留 `padding-bottom`，否则最后一条内容永远被盖住。二者只能有一处负责。

---

*本文件结束。*
