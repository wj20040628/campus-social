# 图标需求与自绘规格

> 依据：`docs/SPEC.md` §8 + `docs/design/design-tokens.json`
> 图标库裁决：`@element-plus/icons-vue@2.3.2`（ADR-003），**全项目锁定这一套，不得混用**

---

## 0. 实测基线（不是估算）

对 `node_modules/@element-plus/icons-vue/dist/global.js` 做正则提取，实测结果：

| 项 | 实测值 | 说明 |
|---|---|---|
| 图标总数 | **450 个** | 此前文档写「294 个」有误，以本表为准 |
| 视觉风格 | **填充型**：`viewBox="0 0 1024 1024"` + `fill="currentColor"` | 不是描边型。自绘图标必须对齐这个规格 |
| 与 EP 组件的关系 | EP 内部图标同源 | 混用第二套库会造成同一页面两种笔触质感 |

> 勘误记录：`design/UIUX.md` 与 `ARCHITECTURE.md` 中「294 个」的数字不准确，已以本文件的 450 为准。

---

## 1. 尺寸档位（全项目统一，不允许出现第四档）

| 档位 | 尺寸 | 用途 | 出现位置 |
|---|---|---|---|
| 行内 | **16px** | 标签前缀、状态文字旁、表格/列表元信息 | `--ys-fontSize-sm` 13px 旁的图标 |
| 按钮内 | **20px** | 按钮、TabBar 项、ActionSheet 项 | 按钮文字左侧 |
| 独立 | **24px** | 空态插图入口、卡片主操作、导航栏 | 空态引导、页面主 CTA |

配套规则：
- 图标与文字的基线对齐用 `vertical-align: -0.125em`（16/20 档）或 `flex` 居中（24 档）
- 图标颜色一律 `currentColor`，**禁止给 SVG 单独填色**（否则违反「颜色只走 `--ys-*`」）

---

## 2. 可直接用 EP 现成图标的语义清单

以下均为 `@element-plus/icons-vue` 实际存在的组件名，可直接 import 使用。

### 2.1 主导航与外壳（TabBar / 侧栏）

| 语义 | EP 组件名 | 档位 |
|---|---|---|
| 首页 | `House` | 24px（TabBar 20px） |
| 圈子 | `ChatDotSquare` | 20px |
| 发布 | `Plus`（圆形底 + 加号，见 §4.2） | 24px |
| 消息 | `ChatDotRound` | 20px |
| 我的 | `User` | 20px |

### 2.2 内容与 Feed

| 语义 | EP 组件名 | 档位 |
|---|---|---|
| 图片 | `Picture` | 16px |
| 视频 | `VideoPlay` | 16px |
| 话题 | `PriceTag` | 16px |
| 圈子/群组 | `ChatDotSquare` | 16px |
| 活动 | `Calendar` | 16px |
| 举报 | `WarnTriangleFilled` | 16px |
| 不感兴趣 | `Close` | 16px |

### 2.3 互动

| 语义 | EP 组件名 | 档位 | 备注 |
|---|---|---|---|
| 搭话（未发） | `ChatLineSquare` | 20px | 核心差异化按钮，见 §4.3 |
| 评论 | `ChatDotRound` | 16px | |
| 分享 | `Share` | 16px | |
| 收藏 | `Star` | 16px | 语义为「收藏」不是「点赞」，勿混用 |
| 已读/完成 | `CircleCheck` | 16px | 已读回执按钮 |
| 未读 | `Bell` | 16px | |

### 2.4 导航与操作

| 语义 | EP 组件名 | 档位 |
|---|---|---|
| 返回 | `ArrowLeft` | 20px |
| 更多 | `MoreFilled` | 20px |
| 关闭 | `Close` | 20px |
| 删除 | `Delete` | 20px |
| 编辑 | `EditPen` | 20px |
| 搜索 | `Search` | 20px |
| 筛选 | `Filter` | 20px |
| 设置 | `Setting` | 20px |
| 刷新 | `Refresh` | 20px |

### 2.5 公告与认证（G3 / F2）

| 语义 | EP 组件名 | 档位 | 备注 |
|---|---|---|---|
| 公告提醒（未读红点） | `BellFilled` | 16px | |
| 已读确认 | `CircleCheck` | 16px | |
| 置顶 | `Top` | 16px | |
| 公告类型-教务 | `Document` | 20px | |
| 公告类型-后勤 | `Tools` | 20px | |
| 公告类型-活动 | `Calendar` | 20px | |
| 公告类型-紧急 | `WarnTriangleFilled` | 20px | |

---

## 3. 必须自绘的图标（2 个）

EP 库**确实缺失**这两个语义，且都不是能用近似图标糊弄过去的。

### 3.1 thumbs-up 点赞

- **为什么不能用 `Star`**：`Star` 在本项目已被定义为「收藏」。点赞和收藏是两个不同的用户动作，语义混用会让用户误解，且后续做收藏功能时无处安放。
- **为什么不用文字替代**：Feed 的点赞是高频操作，需要一眼可辨的视觉锚点。
- **规格**：
  - `viewBox="0 0 1024 1024"`，`fill="currentColor"`
  - 轮廓线宽对齐 EP 图标的视觉重量（EP 填充图标的最小结构宽度约 64/1024）
  - 两个状态：`outline`（未赞）/ `filled`（已赞），**用同一路径切换 fill-opacity 或 path 变体**，不要换图标
  - 实心态用 `--ys-accent`（`#0E7490`），未赞态用 `--ys-muted`

### 3.2 shield 认证盾牌

- **为什么必须自绘**：认证标识是本产品与校园墙的核心分界（PRD F2），用户判断「这人是不是本校的」完全依赖这个符号。EP 只有 `Lock`（锁，语义是「私密」）、`Medal`/`GoldMedal`（奖章，语义是「荣誉」），都不对。
- **规格**：
  - `viewBox="0 0 1024 1024"`，`fill="currentColor"`
  - 盾牌轮廓 + 内部一个简化勾形（勾形用反向镂空，不另加颜色）
  - 尺寸 16px（认证标识旁）/ 24px（个人主页大标识）
  - 颜色：已认证 `--ys-accent`（友声青）；未认证用 `--ys-border` 灰化，**不要用红色或警告色**——未认证不是错误状态
- **配套**：认证状态的文字标签「已认证 · XX 大学」必须与图标同时出现，**不能只给图标**——纯图标对屏幕阅读器不友好，也不满足 AC-12 的「只显示昵称 + 头像 + 认证标识」中的文字部分

---

## 4. 组合图标（非独立图标，需自绘或用容器实现）

### 4.1 未读红点

不是图标，是容器上叠加的元素。

- 尺寸 8px 圆点，`--ys-accent-2`（`#B44A12`，暖橙红，与主色区分）
- 位置：图标右上角，偏移 `-2px, -2px`
- 必须带 `aria-label="N 条未读"`，纯 CSS 圆点对读屏不可见
- 上限显示 `99+`

### 4.2 发布按钮（TabBar 中间凸起）

- 外层 44×44 圆形容器，`--ys-accent` 填充，内含 20px `Plus` 白图标
- 凸起 8px（`--ys-spacing.2`），阴影用 `--ys-shadow.2`
- **凸起会压缩 TabBar 可点区**，实现时用 `position: relative; transform: translateY(-8px)`，不改TabBar 高度，避免顶栏被顶穿

### 4.3 搭话按钮（本项目核心差异化）

- 形态：`ChatLineSquare` 图标 + 文字「搭句话」**并置**，不只用图标
- 理由：PRD 明确要求措辞是「搭句话」不是「加好友」。纯图标无法传达这句话的社交含义，且读屏用户需要文字
- 尺寸：图标 20px + 文字 `--ys-fontSize-sm` 13px，间距 `--ys-spacing.1` 4px
- 颜色：`--ys-accent` 文字 + 同色边框，浅底 `--ys-accent-soft`

---

## 5. 禁则（违反即打回）

1. **禁止任何 emoji 字符充当图标**。包括但不限于回形针类、齿轮类、箭头类字符。检索方式：
   ```
   rg -n "[\x{1F300}-\x{1F9FF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}]" frontend/src/
   ```
   必须零命中。已知现存待修：`views/Chat.vue:144`、`views/Chat.vue:150`、`views/Notify.vue:103`
2. **禁止引入第二套图标库**（Lucide / Font Awesome / Heroicons 等）。需要新语义时自绘，格式对齐 1024 viewBox + fill
3. **禁止给 SVG 单独填色**，一律 `currentColor` 继承
4. **禁止出现 16/20/24 之外的尺寸**
5. **禁止混用 `Star` 表示点赞**（已定义为收藏）

---

## 6. 交付物清单（前端 PR 必须包含）

| 文件 | 说明 |
|---|---|
| `frontend/src/icons/ThumbUp.vue` | 自绘，outline/filled 两态 |
| `frontend/src/icons/VerifiedShield.vue` | 自绘，1024 viewBox |
| `frontend/package.json` | `@element-plus/icons-vue` 从传递依赖提升为直接依赖，锁 `2.3.2` |
| `frontend/src/components/mobile/UnreadDot.vue` | 未读红点组件 |
| 检索截图/输出 | 执行 §5 第 1 条的正则，贴出零命中的结果作为验收证据 |