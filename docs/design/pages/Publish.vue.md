# Publish.vue · 设计提示词

- **目标文件**：`frontend/src/views/Publish.vue`（270 行，改造档位：中，0.4 人日）
- **路由**：`/publish`（新建）· `/post/:id/edit`（编辑，同组件复用）
- **一句话目标**：手机上全屏编辑器，正文、话题、图片预览、字数在同一屏内可见，发布按钮吸底。
- **对应 Spec**：`SPEC.md §7.1` · `§9 AC-11`（未认证禁止发帖）· `§9 AC-19`

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「发动态」/「编辑动态」+ 右 文字按钮「存草稿」（编辑模式下才有；本轮无草稿接口则**不渲染**——不给点不动的按钮） |
| 2 | 正文输入区 | `flex: 1`，内边距 `--ys-space-4` | `el-input type="textarea"`，**不用 `rows` 固定值**，改 `min-height: 200px` + `flex: 1` 撑满剩余高度。字号 `--ys-text-md` 16px（16px 是 iOS 上不触发自动缩放的下限，14px 在 iOS Safari 聚焦时会放大页面） |
| 3 | 图片/视频预览 | 左右 `--ys-space-4`，`gap: var(--ys-space-2)` | 一行 3 列网格，每格 1:1，圆角 `--ys-radius-lg`。每格右上 24px 圆删除按钮（`Close` 16px，热区 44×44，底 `--ys-overlay` 半透明深墨）。视频格左下角 11px 白字「视频」标签 |
| 4 | 话题输入 | 左右 `--ys-space-4`，高 44px | `el-input` + placeholder「话题（可选，用逗号分隔，如：校园生活,美食）」 |
| 5 | 话题 chips 预览 | 左右 `--ys-space-4`，`gap: var(--ys-space-2)` | `--ys-accent-soft` 底 + `--ys-accent` 字，高 24px。超过 5 个显示「+2」 |
| 6 | 字数条 | 右对齐，12px `--ys-meta` | 「0 / 5000」。接近上限（>4800）时变 `--ys-warning`；到 5000 时变 `--ys-danger` + 下方 13px 提示「到 5000 字了，删一点再发」 |
| 7 | **吸底操作栏** | 高 56px + `padding-bottom: var(--ys-safe-bottom)`，上边框 1px `--ys-border`，底 `--ys-surface` | 左：`Picture` 20px「图片」+ `VideoCamera` 20px「视频」（两个 44×44 图标按钮，间距 `--ys-space-2`）；右：「发布」主按钮（44px 高，最小宽 88px） |

**关键结构决策**：正文输入区 `flex: 1` 撑满，图片预览与操作栏不随正文滚动。这样在任何手机高度下，用户都能同时看到「我写了什么」和「发布按钮在哪」。

- 媒体上传走现有 `el-upload` + `http-request`（`Publish.vue:49-65`），但移动端把两个 `el-button`（添加图片/添加视频）换成两个图标按钮，节省横向空间。
- 编辑模式下「取消」按钮（现有 `:68`）移到顶栏左侧的 `ArrowLeft`（`router.back()`），底部只留「保存」。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全屏 flex 列 | `max-width: var(--ys-content-col)` 640px 居中，留白 24px |
| 正文区 | `flex: 1` 撑满 | `min-height: 240px`，随内容增高（不撑满） |
| 操作栏 | 吸底 | 排在内容下方，右对齐，**不吸底** |
| 媒体按钮 | 图标按钮 | 恢复带文字的 `el-button`（「添加图片」「添加视频」） |
| 取消按钮 | 顶栏返回 | 底部左下「取消」次按钮 + 右下「发布」主按钮（现有 `:67-70` 形态保留） |
| 图片预览 | 3 列 | 4 列，每格 120×120（现有 `:224-230` 尺寸保留） |

768–1023px：与移动端同（吸底操作栏），留白 24px，内容限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 编辑器的空态是**引导性 placeholder**，不是空状态区块。placeholder 写具体的话：「今天在校园里遇到什么了？」——禁止「分享你的校园生活…」这种空泛提示（现有 `:9` 就是这个，需改）。<br>编辑模式读取失败：整页替换——`--ys-text-2xl` 主句「这篇帖子读不出来了」+ 副句「可能已经被删掉」+ 次按钮「回上一页」。 |
| **加载骨架屏** | 编辑模式进入时：正文区骨架（5 行文字条）+ 图片区骨架（2 格）。现有实现 `:3` 是一个纯文本「加载中…」——在 0.4 人日档位内可接受，**但建议换成骨架**（编辑器骨架成本很低）。 |
| **错误** | 上传失败：图片格内显示 `--ys-danger-soft` 底 + 16px `WarningFilled` + 11px `--ys-danger`「上传失败」+ 重传按钮（44×44）。**不要**整个 toast 一句「上传失败」（现有 `:169`）就完了——用户不知道是哪张失败了。<br>发布失败：操作栏上方浮出 13px `--ys-danger`「没发出去，再点一次」，**正文与图片全部保留**。<br>命中敏感词（`AC-06`）：发布成功后跳转前 toast「发出去了，正在审核，通过后别人就能看到」；若被驳回则正文上方 `--ys-warning-soft` 底 + `--ys-warning` 文字「这句话可能不太合适，改改再发」。 |
| **权限不足** | 未认证用户（`verify_status != 1`，`AC-11`）进 `/publish`：**不跳转、不隐藏**，而是把正文区替换为一个说明块——自绘 `Shield` 24px `--ys-muted` + `--ys-text-xl` 主句「要先认证校内身份才能发动态」+ 13px 副句「用校园邮箱或学号认证，一分钟就好」+ 主按钮「去认证」（跳 `/onboarding`）。操作栏「发布」按钮置灰。<br>理由：直接跳走会让用户以为产品坏了；给一个明确的「为什么 + 怎么做」是更好的转化。 |
| **内容超长** | 正文 5000 字硬上限（`maxlength`，现有已有）；话题名 64 字截断、最多 5 个（超出提示「最多 5 个话题」）；图片最多 9 张（超出时第 10 张拒绝并 toast「最多 9 张图」）；文件名超长中间省略。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 图片删除 | `--ys-motion-fast` 150ms | `--ys-ease-out` | `opacity` → 0 + `scale(0.96)`，然后移除。（`scale` 是 `transform`，符合「只动 transform/opacity」） |
| 图片上传中 | — | — | 格子内 `--ys-surface-2` 底 + 20px 转圈（`--ys-ease-linear` 匀速）+ 11px `--ys-meta`「上传中」 |
| 字数接近上限 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 字数文字变色 |
| 发布按钮 loading | — | — | spinner + **宽度锁定**（防抖动导致误触相邻元素） |
| 操作栏按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 键盘弹起（正文聚焦） | `--ys-motion-base` 200ms | `--ys-ease-out` | 正文区 `flex: 1` 自动收缩，操作栏用 `visualViewport` 的 `--kb-h` 上移（同 `Chat.vue.md §1.4`） |

- 禁止发布成功的全屏庆祝动画。Toast 一句「发出去了，等别人来搭话吧」即可。
- 禁止图片格子做「瀑布流重排」动画（上传多张时布局跳动）。

---

## 5. 无障碍

- 正文 textarea 有**可见 label**（顶部一行 13px/500「正文」），不能只有 placeholder。`UIUX.md §11` 明确要求「不用 placeholder 当 label」。
- 图片删除按钮 `aria-label="删除第 1 张图片"`。
- 图片格 `alt`：「待发布的图片 1，点右上角删除」。
- 上传中格子 `aria-busy="true"`，完成后 `role="status"` 播报「图片上传完成」。
- 字数条 `aria-live="polite"`，但**只在接近上限（>4800）时才播报**，否则每敲一个字都念一遍。
- 触控热区：操作栏按钮 44×44、删除按钮 44×44、图片格本身 ≥ 80×80。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-warning  --ys-warning-soft  --ys-danger  --ys-danger-soft  --ys-success
--ys-overlay
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6
--ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base
--ys-ease-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（发帖子不是发邀约）、`--ys-cat-*`（话题不是兴趣大类标签）、`--ys-official-*`（用户不能发官方公告）、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-slow` / `--ys-motion-page`。

---

## 7. 前端最容易做错的一点

**iOS 上 14px 的输入框聚焦会导致页面自动缩放**。现有 `el-input` 继承 `--el-font-size-base` = 14px（`Publish.vue:5-12` 的 textarea）。移动端正文输入必须显式设 `font-size: var(--ys-text-md)`（16px）。这是 iOS Safari 的硬行为：<16px 的输入控件聚焦时页面会被放大且不会自动缩回。

---

*本文件结束。*
