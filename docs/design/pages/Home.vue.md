# Home.vue · 设计提示词

- **目标文件**：`frontend/src/views/Home.vue`（219 行，改造档位：中，0.4 人日）
- **路由**：`/`（TabBar 第 1 项）
- **一句话目标**：第一屏直接是内容——公告条 + 兴趣入口 + Feed 单列流，不许有 Hero、不许有引导页。
- **对应 Spec**：`SPEC.md §8.1` 红线（无 AI 模板味）· `§9 AC-17`（LCP < 2.0s）· `§9 AC-19`（空态）· `UIUX.md §1.1` 竞品 C 结论

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 组件选型 |
|---|---|---|---|
| 1 | 顶栏 | `MainLayout` 品牌型 | — |
| 2 | **官方公告条** | 上边距 0，下边距 `var(--ys-space-4)` | 见 §1.1 |
| 3 | 兴趣大类快捷入口 | 下边距 `var(--ys-space-4)` | 4 个 chip 横滑：运动 / 游戏 / 学习 / 社团，chip 高 32px（热区 44×44 靠 padding），圆角 `--ys-radius-full`，各自 `--ys-cat-*` 浅底深字 |
| 4 | Feed Tab | 高 44px，吸顶 `top: var(--ys-topbar-h)` | 自绘分段：「关注」「推荐」「最新」三项（现有只有关注/全部，补一个最新）。选中 = 文字 `--ys-accent` + 底部 2px 指示条 |
| 5 | Feed 流 | 单列，条目间 `border-top: 1px solid var(--ys-border-soft)`，**无卡片盒子** | 复用 `components/PostCard.vue` |
| 6 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | — |

### 1.1 官方公告条（一级入口，`UIUX.md §10.3`）

- 最多显示 **2 条未读**，第 3 条起折叠为一行「还有 3 条公告 · 查看全部」（44px 高，文字 `--ys-accent`）。
- 每条：底 `--ys-official-surface`，圆角 `--ys-radius-xl`，内边距 `var(--ys-space-3) var(--ys-space-4)`，高约 64px：
  ```
  左   深墨徽章 chip（--ys-official-badge-bg 底 + --ys-official-badge-text 字，11px，--ys-radius-sm）
      文字为来源机构名（教务处 / 后勤集团 / 校团委），最多 4 字
  中   标题 14px/500/--ys-fg，1 行截断
  右   图钉 Top 16px（仅置顶时）+ Close 16px 收起（热区 44×44），间距 --ys-space-1
  ```
- 点击整条 → `/announcement/:id`；点击「查看全部」→ `/announcement`。
- 当天收起过的公告不再出现（本地持久化标记，按天失效）。
- **权威感靠深墨徽章，不靠彩色边框**（§8.1 红线 3 的精神：禁止彩色侧条纹）。

### 1.2 搜索入口去哪了

现有 `Home.vue:11-37` 在 Feed 顶部放了一个搜索框 + 两个排序下拉。移动端**全部移除**：

- 搜索移到顶栏右侧 `Search` 图标（跳 `/search`）。
- 排序下拉在移动端删除，改由「推荐 / 最新」两个 Tab 承担排序语义。理由：大学生在手机上不会调「按点赞量倒序」，这是桌面心智。
- 这同时把首屏 DOM 节点从 ~40 降到 ~15，直接贡献 `AC-17` 的 LCP 预算。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全宽 16px | 三栏：`--ys-sidebar-w` 220px（MainLayout 提供）+ 内容列 `--ys-content-col` 640px + 右侧栏 `--ys-aside-w` 320px；≥1280px 锁 `--ys-container-max` 1180px |
| 公告条 | Feed 之上 2 条 | **移到右侧栏**顶部，最多 3 条 + 「查看全部」；Feed 之上只保留「紧急」公告（category=5）一条 |
| 搜索框 | 顶栏图标 | 右侧栏下方常驻搜索框（宽 320px 全宽） |
| 排序下拉 | 已删 | **恢复**「全部」Tab 下的排序下拉（`Home.vue:24-36` 现有实现保留） |
| 兴趣入口 | 横滑 chip | 右侧栏中部「找搭子」卡片：`--ys-surface` + 1px `--ys-border` + `--ys-radius-xl`，内含 4 个大类入口 |
| Feed hover | 无 | 条目底 `--ys-surface-2` |

768–1023px：单列，留白 24px，公告条在 Feed 之上（同移动端）。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 「关注」Tab 空：主句「你关注的人还没发过东西」（`--ys-text-2xl`/600）+ 副句「去关注几个同学，这里就热闹了」+ 主按钮「去关注」（跳 `/search`）。<br>「推荐」Tab 空：主句「这一会儿还没有新动态」+ 副句「你先发一条，别人就有得看了」+ 主按钮「发一条」。<br>**现有两条 `el-empty` 文案（`:55`）无按钮，违反 `AC-19`。** 另：现有「去关注」是 `el-button link`，层级太弱，改主按钮。<br>公告条为空：**不出空态**，整个公告条区块隐藏（`v-if`），不留白块。 |
| **加载骨架屏** | 首屏：公告条骨架 1 条（64px）+ 兴趣 chip 骨架 4 个 + 3 条帖子骨架（44px 圆 + 三行文字条）。**现有实现是 3 个 `el-card` 套 `el-skeleton`**（`Home.vue:41-45`）——卡片盒子与真实 Feed 的「无卡片 + 1px 线」形态不一致，会造成 CLS，必须换成同形态骨架。微光 `--ys-ease-linear`。 |
| **错误** | Feed 加载失败：列表区替换为居中区块——13px `--ys-danger` 文字「动态没加载出来，可能是网络断了」+ 主按钮「重试」。<br>公告条加载失败：**静默降级**（隐藏公告条，不报错）。公告是增强信息，不能因为它挂了就让用户觉得首页坏了。 |
| **权限不足** | 首页对已登录用户始终可见，无权限态。<br>唯一例外：未认证用户（`verify_status != 1`）点「发一条」——按 `AC-11` 禁止发帖，此处**不隐藏按钮**，而是点击后弹 ActionSheet 说明「要先认证校内身份才能发动态」+ 主按钮「去认证」（跳 `/onboarding`）。理由：隐藏按钮会让用户以为产品没有这个功能。 |
| **内容超长** | 公告标题 1 行截断；机构名最多 4 字截断；帖子正文由 `PostCard` 统一 2 行截断 + 「全文」；点赞/评论数 999+ 封顶；时间 `--ys-font-mono` + `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| Feed Tab 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 指示条 `transform: translateX`，内容 `opacity` |
| 公告条收起 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` → 0 + `max-height` → 0（本处允许 `max-height`，因为是删除动画不是展开动画） |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |
| 下拉刷新（新增） | `--ys-motion-base` 200ms | `--ys-ease-out` | 自研：下拉 60px 触发，指示器 20px `Refresh` 图标旋转（`--ys-ease-linear`）。**不做橡皮筋回弹** |
| 帖子 hover（桌面） | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色变化 |

- 禁止 Feed 条目逐个「淡入上浮」的入场动画——无限滚动列表上做逐条入场是性能灾难，且 MOTION_INTENSITY = 3 不允许装饰动画。
- 禁止骨架屏到真实内容的交叉淡入（会掩盖 CLS，掩盖不了体验）。

---

## 5. 无障碍

- Feed Tab `role="tablist"` / `role="tab"` + `aria-selected`。
- 公告条整条是 `<a>` 或 `<button>`，不是 `div @click`。
- 公告条「收起」按钮 `aria-label="收起这条公告，今天不再显示"`。
- 未读公告用 `role="status"` 播报一次。
- 兴趣 chip 是 `<button>`，`aria-label` 含类名（「运动」）。
- 触控热区：Tab 项 44px、公告条整条 ≥ 64px、收起按钮 44×44、chip 44×44（视觉 32px 靠 padding 撑）。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-soft  --ys-accent-on
--ys-danger  --ys-warning  --ys-warning-soft
--ys-official-badge-bg  --ys-official-badge-text  --ys-official-surface
--ys-cat-sport-fg  --ys-cat-sport-bg  --ys-cat-game-fg  --ys-cat-game-bg
--ys-cat-study-fg  --ys-cat-study-bg  --ys-cat-club-fg  --ys-cat-club-bg
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-6
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-1  --ys-shadow-2
--ys-motion-fast  --ys-motion-base
--ys-ease-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（首页不出现搭子邀约；兴趣 chip 走 `--ys-cat-*`）、`--ys-success`（首页无成功态）、`--ys-shadow-3`（无浮层）、`--ys-motion-slow` / `--ys-motion-page`。

---

## 7. 前端最容易做错的一点

**骨架屏形状必须对齐真实条目**。现有 `Home.vue:41-45` 用 `el-card` 包 `el-skeleton :rows="3"`，而真实 Feed 是「无卡片 + 1px 线 + 44px 头像」。骨架与真实形态不一致时，加载完成那一刻会产生明显位移，直接打爆 `AC-17` 的 LCP/CLS 预算。改成：骨架条目 = 44px 圆 + 两行文字条（宽 40% / 90%），条目间也是 1px `border-top`。

---

*本文件结束。*
