# Circles.vue · 设计提示词

- **目标文件**：`frontend/src/views/Circles.vue`（199 行，改造档位：中，0.4 人日）
- **路由**：`/circles`（TabBar 第 2 项）
- **一句话目标**：圈子列表在手机上从「auto-fill 卡片网格」改成「单列全宽行」，创建圈子弹窗改成全屏抽屉。
- **对应 Spec**：`SPEC.md §7.1` · `§8.1` 红线 2（禁止 `:fullscreen` 之外的固定宽弹窗——本页有 `el-dialog width="420px"`）· `§9 AC-15/19`

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 品牌型，右 `Search`（搜索圈子）+ `Plus`（创建圈子，44×44 图标按钮） |
| 2 | 「我加入的」分组（有数据时） | 下边距 `--ys-space-4` | 分组头 40px：13px/500/`--ys-fg-2`「我加入的」+ 右侧计数。组内单列行 |
| 3 | 「全部圈子」分组 | — | 分组头同上，文案「全部圈子」 |
| 4 | 圈子行 | 高 72px，左右 `--ys-space-4`，行间 `border-top: 1px solid var(--ys-border-soft)` | 见 §1.1 |
| 5 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | — |

### 1.1 圈子行结构（单列全宽，不是卡片网格）

```
左   48px 圆角方形头像（--ys-radius-lg）
中   第 1 行：圈子名 16px/500/--ys-fg（1 行截断）
     第 2 行：13px --ys-meta「128 成员 · 圈主：张三」
右   「加入」胶囊按钮（--ys-accent 描边 + --ys-accent 字，高 44px，最小宽 72px）
     已加入时换成 --ys-surface-2 底 + --ys-fg-2 字「已加入」+ 16px CircleCheck
```

**为什么移动端必须单列**：现有 `:160-165` 用 `grid-template-columns: repeat(auto-fill, minmax(260px, 1fr))`。在 375px 下它会退化成单列，但**每个卡片仍带 `el-card` 的边框、圆角、内边距与 hover 阴影**——即「一行一个卡片盒子 + 左右留白」，视觉上是三个孤立方块堆叠，浪费 32px 垂直空间且不像列表。改成行式（`border-top` 分组）后同屏能多看 1.5 条。

### 1.2 「创建圈子」弹窗改造

**移动端：全屏抽屉**（`el-drawer direction="btt" size="100%"`）：

```
抽屉头 56px：左「取消」右「创建」，中「创建圈子」
内容区：label 13px/500/--ys-fg-2「圈子名称」
        el-input 高 44px，maxlength 64
        placeholder「如 篮球圈 / 考研圈 / 摄影社」（现有 :51 文案保留，是真实可用的示例）
        下方 13px --ys-meta hint「名字起清楚一点，别人搜得到」
底部：主按钮「创建」全宽 44px，padding-bottom: calc(var(--ys-space-4) + var(--ys-safe-bottom))
```

桌面端保留 `el-dialog width="420px"`（≥1024px 不溢出，符合红线 2）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 列表形态 | 单列行 | **卡片网格** `grid-template-columns: repeat(auto-fill, minmax(260px, 1fr))`，`gap: var(--ys-space-4)`（现有 `:160-165` 保留） |
| 容器 | 全宽 16px | `max-width: 900px` 居中（现有 `:147` 保留），留白 24px |
| 分组 | 「我加入的」+「全部圈子」 | **不分组**，单一网格（桌面一屏能看到 9–12 个卡片，分组反而增加滚动） |
| 顶部工具栏 | 顶栏图标 | 恢复 `el-button`「创建圈子」（现有 `:5-9`，带 `Plus` 图标 round 按钮） |
| 卡片 hover | 无 | `box-shadow: var(--ys-shadow-2)` + 150ms `--ys-ease-out`（现有 `:168-169` 的 `box-shadow 0.2s` 改为 Token） |
| 创建弹窗 | 全屏抽屉 | `el-dialog width="420px"` |

768–1023px：双列网格（`minmax(260px, 1fr)` 自然退化），留白 24px。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 全部圈子为空（冷启动）：主句「还没有圈子」（`--ys-text-2xl`/600）+ 副句「建一个篮球圈、考研圈或者你们院的圈子，名字你定」+ 主按钮「创建圈子」（打开抽屉）。<br>搜索无结果：主句「没找到叫这个名字的圈子」+ 副句「换个词，或者自己建一个」+ 主按钮「创建圈子」。<br>**现有文案「还没有圈子」（`:38`）无按钮，违反 `AC-19`。**<br>「我加入的」为空：整组隐藏（`v-if`），不留空分组头。 |
| **加载骨架屏** | 6 条圈子行骨架（48px 圆角方块 + 两行文字条 60% / 40%），微光 `--ys-ease-linear`。桌面端改为 6 个卡片骨架。 |
| **错误** | 加载失败：整页替换——13px `--ys-danger`「圈子列表没拉出来，可能是网络断了」+ 主按钮「重试」。<br>创建失败：抽屉内输入框下方 13px `--ys-danger`「没创建成功，再点一次」，**输入的名字保留**。<br>加入失败：按钮回到「加入」态 + toast「没加上，网络好像断了」。 |
| **权限不足** | 圈子列表对已登录用户始终可见。<br>未认证用户（`AC-11`）点「创建圈子」：ActionSheet 说明「要先认证校内身份才能建圈子」+ 主按钮「去认证」。<br>理由同 `Publish.vue`：给「为什么 + 怎么做」，不给沉默失败。 |
| **内容超长** | 圈子名 1 行截断；圈主昵称 1 行截断；成员数 999+ 封顶；圈子名输入 64 字硬上限（`maxlength` 已有）。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 加入按钮状态切换 | `--ys-motion-base` 200ms | `--ys-ease-out` | 文字与底色交叉淡入，**宽度锁定**（「加入」→「已加入」宽度不变） |
| 加入按钮 loading | — | — | spinner + 宽度锁定 |
| 抽屉升起（创建圈子） | `--ys-motion-slow` 280ms | `--ys-ease-in-out` | `transform: translateY(100%) → 0` |
| 遮罩淡入 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` |
| 卡片 hover（桌面） | `--ys-motion-fast` 150ms | `--ys-ease-out` | `box-shadow` + `border-color` |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |

- 禁止卡片网格在加入成功后重排（会跳动）。
- 禁止创建成功后的全屏庆祝；Toast「圈子建好了，去发第一条吧」。

---

## 5. 无障碍

- 圈子行整条是 `<button>` 或 `<a>`，不是 `div @click`（现有 `:17` 的 `@click="goDetail"` 挂在 `el-card` 上，需改）。
- 「加入」按钮 `@click.stop`，`aria-label` 含圈子名「加入 篮球圈」。
- 「已加入」不能只有绿色/灰色差异：文字「已加入」+ `CircleCheck` 图标双通道。
- 分组头 `<h2>` + 13px/500。
- 触控热区：圈子行 72px、加入按钮 44×44、顶栏图标 44×44。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-danger
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-xl  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-6
--ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-1  --ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-container-max  --ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky  --ys-z-overlay  --ys-z-drawer
```

**本页不使用**：`--ys-accent-2` 系（圈子不是搭子邀约）、`--ys-cat-*`（圈子名不是兴趣标签）、`--ys-official-*`、`--ys-warning` / `--ys-warning-soft`（本页无警告态）、`--ys-shadow-3`（遮罩由 `mobile-shell.md` 管）、`--ys-motion-page`。

---

## 7. 前端最容易做错的一点

现有 `:17` 把 `@click="goDetail(c.id)"` 挂在 `el-card` 上，`el-card` 渲染成 `div`。两个后果：键盘用户 Tab 不到、屏幕阅读器不知道它可点。移动端改成行式后必须换成真正的 `<button>` 或 `<a>`，同时保留「加入」按钮的 `@click.stop`。

---

*本文件结束。*
