# AdminAudit.vue · 设计提示词

- **目标文件**：`frontend/src/views/AdminAudit.vue`（226 行，改造档位：中，0.4 人日）
- **路由**：`/admin/audit`（`meta: { requiresAuth: true, requiresAdmin: true, back: true, hideTabBar: true }`）
- **一句话目标**：让管理员在手机上能查操作日志——筛选条件收进一行可横滑的 chip，日志条目改成纵向两行结构，砍掉全量用户下拉。
- **对应 Spec**：`SPEC.md §0 OD-19`（后台页必须支持移动端）· `§9 AC-20`

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 尺寸 | 组件选型 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「操作日志」+ 右 `Filter`（展开/收起筛选区，`aria-expanded`） |
| 2 | 筛选区（可折叠，默认收起） | 展开后约 200px，底 `--ys-surface`，下边框 1px `--ys-border` | 见 §1.1 |
| 3 | 已选条件回显条（筛选生效时） | 高 36px，仅展开态之下常驻 | 一行横滑 chips：`--ys-accent-soft` 底 + `--ys-accent` 字 + 右侧 16px `Close`；末尾一个「清空」文字按钮 |
| 4 | 日志列表 | 单列，条目间 `border-top: 1px solid var(--ys-border-soft)`，**无卡片盒子** | 见 §1.2 |
| 5 | 底部兜底 | `padding-bottom: calc(var(--ys-space-6) + var(--ys-safe-bottom))` | 本页 `hideTabBar: true`，不用加 TabBar 高度 |

### 1.1 筛选区（折叠面板）

三个字段**纵向排列**，`label-position="top"`：

| 字段 | 组件 | 规格 |
|---|---|---|
| 动作 | `el-select` 全宽 | 高 44px，12 个动作项（现有 `ACTIONS` 数组）。**不用下拉搜索**——12 项在手机上全屏下拉列表更好点 |
| 用户 | `el-input` + 触发搜索 | **替换现有的「全量用户下拉」**（`AdminAudit.vue:11-13`）。原因见 §7 |
| 关键字 | `el-input` 全宽 | 高 44px，placeholder「搜 detail / IP / action」，回车即查 |
| 底部 | 「重置」次按钮 + 「查询」主按钮 | 各 44px，等宽，`gap: var(--ys-space-3)` |

展开/收起动画：`grid-template-rows: 0fr → 1fr`，`--ys-motion-base` 200ms `--ys-ease-out`。**禁止用 `max-height: 500px` 的魔法值**。

### 1.2 日志条目（纵向两行结构）

现有实现是「头像 + 一行三列 + 详情 + meta」的横向布局（`:20-36`），在 375px 下会挤成一行看不懂。改为：

```
行内左   34px 圆形头像（flex-shrink: 0）
行内右   第 1 行：操作者昵称 14px/500/--ys-fg（1 行截断） + 动作标签（12px，圆角 --ys-radius-sm）
         第 2 行：detail 13px/--ys-fg-2，最多 2 行截断，overflow-wrap: anywhere
         第 3 行：12px --ys-meta：时间（tabular-nums） + 「· IP 10.20.x.x」 + 「· 目标 #123」
```

- 条目内边距 `var(--ys-space-4)` 上下、`var(--ys-space-4)` 左右。
- 条目**不用 hover 态**（移动端无 hover）；桌面端 hover 底 `--ys-surface-2`。
- 动作标签配色（沿用现有 `actionTag` 语义，但换成 Token）：
  - `review_approve`：`--ys-success-soft` 底 + `--ys-success` 字
  - `review_reject` / `delete_post` / `blacklist`：`--ys-danger-soft` 底 + `--ys-danger` 字
  - `login` / `register`：`--ys-accent-soft` 底 + `--ys-accent` 字
  - 其余：`--ys-surface-2` 底 + `--ys-fg-2` 字
  - **标签内文字就是动作名**（「审核通过」「删帖」「拉黑」），不靠颜色猜。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 筛选区 | 折叠，默认收起 | **常驻展开**，横排一行（`AdminAudit.vue:7-16` 现有形态保留），`max-width: var(--ys-content-col)` |
| 用户筛选 | 输入框 | **恢复 `el-select` 下拉**（桌面上全量用户下拉是可用的，且管理员在桌面上就是要精确选人） |
| 日志条目 | 纵向三行 | 恢复横向：头像 + 主体（第 1 行操作者+标签+目标，第 2 行 detail，第 3 行 meta） |
| 容器 | 全宽 16px 留白 | `max-width: var(--ys-container-max)`，留白 24px |
| hover | 无 | 底 `--ys-surface-2`，150ms `--ys-ease-out` |

768–1023px：与移动端同，留白 24px，日志限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 两种，必须区分：<br>1) 无筛选 + 库里真没日志：主句「还没有任何操作记录」（`--ys-text-2xl`/600）+ 副句「有人发帖、登录、被审核之后这里就有了」+ 次按钮「回首页」。<br>2) 有筛选但无匹配：主句「这几个条件下没有记录」+ 副句「把条件放宽一点试试」+ 主按钮「清空筛选」。<br>**现有文案「没有匹配的日志」无按钮，违反 `AC-19`。** |
| **加载骨架屏** | 8 条日志骨架（34px 圆 + 三行文字条，第三行短），微光 `--ys-ease-linear`。**首屏不用 spinner。** 翻页加载更多时，只在列表底部追加 3 条骨架，不动已有内容。 |
| **错误** | 查询失败：列表区替换为居中区块——`WarningFilled` 20px `--ys-danger` + 13px `--ys-danger` 文字「日志没拉出来，可能是网络断了」+ 主按钮「重试」。<br>用户搜索失败：输入框下方 13px `--ys-danger`「没搜到这个人，换个关键字试试」，不影响主查询。 |
| **权限不足** | `isAdmin === false` 或 API 403 `NOT_ADMIN`：整页替换——自绘 `Shield` 24px `--ys-muted` + 主句「这个页面只有管理员能看」+ 副句「如果你应该是管理员，找负责人开一下权限」+ 次按钮「回首页」。与 `AdminReview.vue` 完全一致（两页共用同一个权限不足区块，抽成组件）。 |
| **内容超长** | 昵称 1 行截断；detail 2 行截断（`overflow-wrap: anywhere`，日志里有长 JSON 与 URL）；IP 原样显示不截断（`--ys-font-mono`）；目标 ID 999999+ 不封顶（ID 不是计数）；时间 `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 筛选区展开/收起 | `--ys-motion-base` 200ms | `--ys-ease-out` | `grid-template-rows: 0fr ↔ 1fr` |
| `Filter` 图标态切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 激活时图标色 `--ys-accent` |
| 条件 chip 删除 | `--ys-motion-fast` 150ms | `--ys-ease-out` | chip `opacity` → 0 后移除，剩余 chips 用 flex 重排（不加动画，避免抖动） |
| 查询按钮 loading | — | — | spinner + 宽度锁定 |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |
| 条目 hover（桌面） | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色变化 |

- 本页是**只读**页面，没有任何写操作，因此**不做任何撤销/确认交互**。
- 禁止筛选区展开时把页面滚到顶（会让管理员失去上下文）。

---

## 5. 无障碍

- 筛选区触发器是 `<button aria-expanded="true|false" aria-controls="audit-filter">`。
- 每条件是 `<button>` + `aria-label="移除筛选条件：动作 审核通过"`。
- 日志列表是 `<ul>/<li>`；动作标签的文字就是动作名，红/绿只是辅助（色觉障碍适配）。
- 顶栏 `Filter` 按钮 `aria-label="筛选"`。
- 触控热区：筛选字段 44px、查询/重置 44px、chip 关闭按钮 44×44（chip 视觉高 32px，热区靠 padding 撑到 44）、日志条目整体 ≥ 56px 高。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-soft  --ys-accent-on
--ys-success  --ys-success-soft  --ys-danger  --ys-danger-soft  --ys-warning
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-2xl
--ys-lh-base  --ys-lh-sm  --ys-ls-sm
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-fast  --ys-motion-base
--ys-ease-out  --ys-ease-linear
--ys-focus-ring
--ys-container-max  --ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系、`--ys-cat-*`、`--ys-official-*`、`--ys-shadow-1`（本页无卡片盒子）、`--ys-shadow-3`（无浮层）、`--ys-motion-slow` / `--ys-motion-page`（无抽屉无路由过渡）。

---

## 7. 必须砍掉的一点：全量用户下拉

`AdminAudit.vue:11-13` 用 `mentionCandidates('')`（`AdminAudit.vue:134-141`）拉取**全部用户**填进 `el-select`。这在移动端有三个问题：

1. 一次请求拉全表，管理员手机上流量与等待都不可接受；
2. EP 的 `el-select` 全量下拉在移动端是**页面级弹层**，12 个动作 + 全量用户 = 两个巨型弹层；
3. 它是本页唯一的「非只读」行为（一次无意义的大查询）。

改为：移动端用**关键字输入框**（输入昵称关键字，`mentionCandidates(kw)` 本身已支持 kw 参数，后端不用改）。桌面端保留下拉。

---

*本文件结束。*
