# PostDetail.vue · 设计提示词

- **目标文件**：`frontend/src/views/PostDetail.vue`（485 行，改造档位：中，0.4 人日）
- **路由**：`/post/:id`（`meta: { back: true, hideTabBar: true }`）
- **一句话目标**：正文线性流 + 吸底评论输入，让评论框在键盘弹起时仍贴着键盘上沿。
- **对应 Spec**：`SPEC.md §7.1` · `§9 AC-15`
- **备注**：Spec §7.2 明确写了「行数不等于难度：它是线性内容流，改造量反而更低」

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「帖子详情」+ 右 `MoreFilled`（ActionSheet：举报 / 复制链接 / 不看此作者） |
| 2 | 作者行 | 内边距 `--ys-space-4`，下方 `--ys-space-3` | 44px 头像 + 右：昵称 14px/500（1 行截断）+ 时间 12px `--ys-meta`；最右：状态标签（审核中 / 已驳回） |
| 3 | 正文 | `--ys-text-md` 16px / `--ys-lh-md` 1.6，左右 `--ys-space-4`，`overflow-wrap: anywhere` | 保留换行 `white-space: pre-wrap` |
| 4 | 话题 chips | 左右 `--ys-space-4`，`gap: var(--ys-space-2)` | `--ys-accent-soft` 底 + `--ys-accent` 字，高 24px，圆角 `--ys-radius-md`，点击跳 `/topic/:name` |
| 5 | 媒体区 | 左右 `--ys-space-4` | 1 图：全宽自适应，圆角 `--ys-radius-lg`，最大高 60vh；2 图：双列；≥3 图：三列网格 `gap: var(--ys-space-1)`。视频：全宽 16:9 |
| 6 | 操作条 | 高 48px，左右 `--ys-space-4`，上下 `--ys-space-4` | 左：点赞（`IconLike` 自绘 20px + 计数）；中：评论（`ChatLineSquare` 20px + 计数）；右：作者本人时是「编辑 / 删除」，他人时是「搭句话」主按钮（44px 高，最小宽 88px） |
| 7 | 分隔 | 1px `--ys-border` 全宽 | — |
| 8 | 评论列表 | 条目内边距 `--ys-space-4`，条目间 `border-top: 1px solid var(--ys-border-soft)` | 32px 头像 + 昵称 13px/500 + 正文 14px（最多 6 行 + 展开）+ 时间 12px `--ys-meta`。回复关系用「回复 XXX」13px `--ys-muted` 前缀 |
| 9 | **吸底评论输入区** | 见 §1.1 | 固定在屏幕底部 |

### 1.1 吸底评论输入区（本页核心）

```
[ @ 提及浮层（展开时） 高 120px，底 --ys-surface，上边框 1px --ys-border ]
[ 输入行 高 56px：左侧 textarea（1-4 行自适应，--ys-text-base）
                  右侧 44×44 的 @ 按钮（MoreFilled 或自绘）
                  右侧 44×44 发送按钮（IconSend 自绘 20px，--ys-accent 底圆形） ]
```

- 位置：`position: sticky; bottom: 0`，底 `--ys-surface`，上边框 1px `--ys-border`。
- 键盘弹起：与 `Chat.vue` 同一套 `visualViewport` 方案（见 `Chat.vue.md §1.4`），`transform: translateY(calc(-1 * var(--kb-h, 0px)))`，`--ys-motion-base` 200ms `--ys-ease-out`。
- 安全区：`padding-bottom: calc(var(--ys-space-2) + var(--ys-safe-bottom))`。
- 评论列表底部留白：`calc(56px + var(--ys-space-4) + var(--ys-safe-bottom))`，保证最后一条评论不被输入区盖住。
- **发送按钮在键盘弹起时必须仍可见**——这是评论区最常见的 bug：键盘一弹，发送按钮被顶出视口。

### 1.2 @ 提及浮层

现有实现（`:70-85`）是横排 52px 头像条。移动端保留横排但：头像降到 40px、浮层高 96px、横向滚动、`--ys-surface-2` 底。文字提示沿用现有两条（「无匹配用户」「还没有可 @ 的人…」），符合「说人话」要求。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全宽 16px | `max-width: var(--ys-content-col)` 640px 居中，留白 24px |
| 评论输入区 | 吸底 | **不吸底**，排在评论列表下方（桌面没有键盘遮挡问题，吸底反而侵占阅读区） |
| 媒体区 | 三列网格 | 2 图并排、3 图三列、≥4 图两列；单图最大宽 100%、最大高 70vh |
| 操作条 | 图标 + 计数 | 同上，但 hover 时图标区底 `--ys-surface-2` |
| 点赞 | `IconLike` | 同，hover 150ms `--ys-ease-out` |
| 右侧栏 | 无 | ≥1280px 时右侧 320px 放「相关帖子」。**本轮若无推荐数据则不渲染**（空栏位比没有更糟） |

768–1023px：与移动端同结构，留白 24px，内容限宽 640px 居中，评论输入区仍吸底。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 评论为空：主句「还没有人搭话」（`--ys-text-lg`/600，不用 3xl——评论区空态不该抢正文的视觉重量）+ 副句「说点什么，楼主在等回应」（13px `--ys-muted`）+ 主按钮「说一句」（聚焦评论输入框）。<br>**现有文案「暂无评论」（`:65`）违反 `AC-19`，必须换。** |
| **加载骨架屏** | 作者行骨架（44px 圆 + 两行条）+ 正文骨架（5 行，宽度 100%/96%/88%/100%/60%）+ 3 条评论骨架（32px 圆 + 两行条）。微光 `--ys-ease-linear`。 |
| **错误** | 帖子不存在/已下架：整页替换——`--ys-text-2xl` 主句「这篇帖子不见了」+ 副句「可能已经被删掉，或者链接错了」+ 次按钮「回首页」。<br>评论发送失败：输入区上方浮出 13px `--ys-danger`「没发出去，再点一次发送」，**草稿必须保留**。<br>点赞失败：点赞图标回弹到原状态（`--ys-motion-fast`），toast「没点上，网络好像断了」。 |
| **权限不足** | 1) 未认证用户点「搭句话」：按 `AC-11` 禁止发起搭话 → 不隐藏按钮，点击后 ActionSheet 说明「要先认证校内身份才能搭话」+ 主按钮「去认证」。<br>2) 对方关闭陌生人消息（`AC-05`）：「搭句话」按钮置灰，按钮下方 13px `--ys-meta`「对方已关闭陌生人消息」。<br>3) 当日额度用尽（`AC-04`）：按钮置灰 + 13px `--ys-meta`「今天打招呼的次数用完了，明天再来」。<br>4) 非作者点「编辑 / 删除」：这两个按钮对非作者**根本不渲染**（现有 `v-if="post.author?.id === myId"` 已正确）。 |
| **内容超长** | 昵称 1 行截断；正文不截断（`overflow-wrap: anywhere`，长 URL 不撑破）；评论正文 6 行 + 展开；话题 chip 超过 5 个显示「+2」；点赞/评论数 999+ 封顶；时间 `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 点赞 | `--ys-motion-instant` 80ms | `--ys-ease-out` | 图标 `currentColor` 从 `--ys-muted` 变 `--ys-accent` + 内部填充切换。**禁止放大回弹**（MOTION_INTENSITY = 3） |
| 键盘弹起 / 收起 | `--ys-motion-base` 200ms | `--ys-ease-out` | `transform: translateY`，不动 `height` / `bottom` |
| @ 浮层展开 | `--ys-motion-base` 200ms | `--ys-ease-out` | `grid-template-rows: 0fr → 1fr` |
| 新评论插入 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` + `translateY(8px)`，然后滚到该条 |
| 图片点开预览 | `--ys-motion-base` 200ms | `--ys-ease-out` | EP `el-image` 预览器，不做自定义 |
| 操作条按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |

- 禁止评论区「楼层」缩进超过 2 层（嵌套回复在 375px 下不可读）。
- 禁止图片加载时的模糊占位（LQIP）——装饰性模糊与 2 秒加载预算冲突，用 `--ys-surface-2` 纯色占位。

---

## 5. 无障碍

- 正文区 `role="article"`；评论列表 `<ul>/<li>`。
- 点赞按钮 `aria-pressed="true|false"`（切换型按钮），`aria-label` 含计数「点赞，23」。
- 单独图标按钮（@ 、发送）必须 `aria-label`。
- 图片 `alt`：帖子图片 `alt="帖子附图 1，共 3 张"`；头像 `alt="张三的头像"`。
- 发送成功后 `role="status"` 播报「评论已发布」。
- 触控热区：操作条按钮 44×44、发送按钮 44×44、@ 按钮 44×44、评论条目整体 ≥ 56px。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-success-soft  --ys-warning  --ys-warning-soft  --ys-danger  --ys-danger-soft
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg
--ys-lh-base  --ys-lh-md  --ys-ls-sm
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6
--ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer
```

**本页不使用**：`--ys-accent-2` 系、`--ys-cat-*`（帖子详情页不出现兴趣大类标签）、`--ys-official-*`、`--ys-shadow-1`（无卡片盒子）、`--ys-shadow-3`（ActionSheet 由 `mobile-shell.md` 管）、`--ys-motion-page`（无路由过渡）。

---

## 7. 前端最容易做错的一点

`hideTabBar: true` 之后，评论输入区若用 `position: fixed; bottom: 0`，在 iOS 上键盘弹起时 `fixed` 元素会跟着视口乱飘。必须用 **`position: sticky; bottom: 0`** 配合 `visualViewport` 的 `--kb-h` 位移，或者用 `position: fixed` 但把 `--kb-h` 减在 `bottom` 上（`bottom: var(--kb-h, 0px)` 而非 `transform`）。两种都行，**不要混用**——混用时会出现「键盘弹起两次，输入区跑两次」的经典 double-shift bug。

---

*本文件结束。*
