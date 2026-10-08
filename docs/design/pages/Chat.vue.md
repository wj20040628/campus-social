# Chat.vue · 设计提示词

- **目标文件**：`frontend/src/views/Chat.vue`（1072 行，改造档位：**重**，2.0 人日）
- **路由**：`/chat`（会话列表）· **新增子路由** `/chat/t/:kind/:id`（会话页，`kind` = `dm` | `group`）
- **一句话目标**：把「左 280px 会话列表 + 右消息窗」的双栏主从，改成手机上「列表页 ↔ 会话页」单栏路由切换，并让输入框在键盘弹起时不被吞掉。
- **对应 Spec**：`SPEC.md §7.1`（重档）· `§7.2`（三处硬编码：`el-aside width="280px"` / `height: calc(100vh - 60px)` / `el-dialog width="380px"`）· `§9 AC-01/02/03/05/16` · §8.1 红线
- **对应 UIUX**：`UIUX.md §10.1`（G1 陌生人破冰）

---

## 1. 移动端布局（<640px，默认基准）

### 1.1 列表页 `/chat`（单栏，全宽）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 组件选型 |
|---|---|---|---|
| 1 | 顶栏 | 由 `MainLayout` 提供（品牌型），本页不重复实现 | — |
| 2 | 破冰额度条 | 高 32px，横向内边距 `--ys-space-4`，底 `--ys-accent-soft`，圆角 `--ys-radius-md`，文字 `--ys-text-xs` / `--ys-accent` | 自绘 div。内容：「今天还能主动打招呼 4 次」+ 右侧「额度说明」文字按钮（44×44 热区） |
| 3 | 陌生人消息**折叠分组** | 分组头高 48px，底 `--ys-surface`，上边距 `--ys-space-3` | `<button>` 分组头：左 `ChatDotRound` 20px + 文字「陌生人消息」+ 计数徽章；右 `CaretBottom` 20px（展开时旋转 180°）。**默认收起** |
| 3a | 分组内条目（展开后） | 每条 72px 高，行间 `border-top: 1px solid var(--ys-border-soft)` | 头像 40px + 昵称 14px/500 + 预览 13px `--ys-muted` 1 行截断 + 右侧 44×44 的「忽略」文字按钮 |
| 3b | 分组头右侧「全部忽略」 | 44×44 | 文字按钮，文案「全部忽略」，色 `--ys-muted` |
| 4 | 私信分组 | 分组头 40px，文字 13px/500/`--ys-fg-2`：「私信」 | 同上 |
| 4a | 会话条目 | 72px 高 | 左 44px 头像（带 `el-badge` 未读，max 99）+ 中 昵称 14px/500 + 预览 13px `--ys-muted` 1 行截断 + 右 时间 12px `--ys-meta`（`font-variant-numeric: tabular-nums`） |
| 5 | 群组分组 | 同 4 | 头像改成圆角 `--ys-radius-lg` 的方形（`g-avatar`） |
| 6 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | — |

- 三个分组全部为空时：见 §3 空态。
- 「我关注的」折叠区块（现有 `Chat.vue:55-82`）**移动端删除**——它在 375px 下要嵌套两层折叠，认知负荷超 4 项上限。找人改走 `/search`。

### 1.2 会话页 `/chat/t/:kind/:id`（单栏，沉浸式）

| 行 | 区块 | 尺寸 | 说明 |
|---|---|---|---|
| 1 | 会话页顶栏 | 56px | `MainLayout` 返回型顶栏：左 `ArrowLeft`（44×44）+ 对方昵称（1 行截断）+ 右 `MoreFilled`（44×44，打开 ActionSheet：查看主页 / 清空会话 / 拉黑） |
| 2 | **来源上下文条** | 高 32px，底 `--ys-surface-2`，左右 `--ys-space-4` | `Top`（图钉）16px + 12px `--ys-fg-2` 文字「来自：周五三号场羽毛球」+ 右侧 44×44 关闭。这是「不加好友也能聊」能成立的由头，**不可删** |
| 3 | 消息列表 | `flex: 1`，`overflow-y: auto`，`-webkit-overflow-scrolling: touch` | 见 §1.3 |
| 4 | 引用条（仅回复时） | 高 36px，底 `--ys-surface-2`，左侧 2px 内缩 | 文案「回复 张三：内容…」1 行截断 + 右侧 44×44 取消 |
| 5 | 输入区 | 见 §1.4，键盘方案 | textarea 自适应 1–4 行 + 右侧发送按钮 |

**关键结构决策**：会话页 **`meta: { hideTabBar: true }`**。理由：TabBar 56px + 输入区 56px 会同时占据底部，iPhone 上再加 34px 安全区，消息列表只剩不到半屏。会话页是全屏会话，返回手势用顶栏返回箭头。

### 1.3 消息气泡

| 元素 | 规格 |
|---|---|
| 我方气泡 | 底 `--ys-accent`，文字 `--ys-accent-on`，圆角 `--ys-radius-xl`，右上圆角 `--ys-radius-sm`（尾巴感），最大宽 72% |
| 对方气泡 | 底 `--ys-surface`，文字 `--ys-fg`，1px `--ys-border`，圆角 `--ys-radius-xl`，左上圆角 `--ys-radius-sm` |
| 气泡正文 | `--ys-text-md` 16px / `--ys-lh-md` 1.6 / `overflow-wrap: anywhere` |
| 气泡脚注 | 时间 12px `--ys-meta`；已读/未读 12px |
| 已读态 | 对方气泡上的已读标记用 `--ys-success`；**禁止**沿用现有的 `#b3f0c8`（`Chat.vue:1027` 硬编码） |
| 撤回态 | 底 `--ys-surface-2`，文字 `--ys-muted`，13px，居中，文案「这条消息已撤回」 |
| 群消息发送者 | 气泡上方 12px `--ys-meta`，1 行截断 |

气泡操作（撤回 / 引用）：**长按气泡**弹出 ActionSheet（不是常驻图标按钮——常驻会让每条消息右侧多两个 44px 热区，在 375px 下挤压正文宽度）。撤回用 EP `RefreshLeft`，引用用 EP `Paperclip`。**同时替换现有源码里的两个字符图标**（`Chat.vue:144` 的回形针字符与 `:150` 的左箭头字符），二者均命中 P0「禁止 emoji/字符充当功能图标」，见 `icons-req.md` §5。

### 1.4 键盘弹起与 iPhone 安全区（本页最难的两点）

```css
/* 1) 视口高度：100vh 被地址栏遮挡，一律 dvh */
.chat-page { height: 100vh; height: 100dvh; }

/* 2) 输入区：贴底，键盘弹起时上移。--kb-h 由 JS 写入 */
.msg-input {
  position: sticky;
  bottom: 0;
  padding: var(--ys-space-2) var(--ys-space-4);
  padding-bottom: calc(var(--ys-space-2) + var(--ys-safe-bottom));
  background: var(--ys-surface);
  border-top: 1px solid var(--ys-border);
  transform: translateY(calc(-1 * var(--kb-h, 0px)));
  transition: transform var(--ys-motion-base) var(--ys-ease-out);
}
```

JS 侧（`onMounted` 注册、`onBeforeUnmount` 注销）：

```js
// iOS Safari 不派发可靠的 window.resize，用 visualViewport
const vv = window.visualViewport
if (vv) {
  const sync = () => {
    const kb = Math.max(0, window.innerHeight - vv.height - vv.offsetTop)
    document.documentElement.style.setProperty('--kb-h', `${kb}px`)
    // 键盘弹起时把最新一条滚到可视区
    if (kb > 0) requestAnimationFrame(scrollBottom)
  }
  vv.addEventListener('resize', sync)
  vv.addEventListener('scroll', sync)
}
```

**为什么是这个方案**：`window.innerHeight` 在 iOS 键盘弹起时**不变**，只有 `visualViewport.height` 变。用 `resize` 事件监听 `window` 会导致安卓正常、iOS 输入框被键盘盖住——这是移动端 IM 最常见的线上事故。

安全区三条：
- 消息列表底部：`padding-bottom: var(--ys-space-2)`（输入区已含安全区，列表不用再加）。
- 输入区自身：`padding-bottom: calc(var(--ys-space-2) + var(--ys-safe-bottom))`，无键盘时贴在 iPhone 底部横条之上。
- `index.html` 必须补 `viewport-fit=cover`，否则 `env()` 恒为 0（见 `_00-通用约定.md` §3）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 结构 | 单栏路由切换 | **保留双栏主从**，但宽度改 CSS：`aside { width: var(--ys-aside-w) }` = 320px（不是 280px，280 在 1024 下余量不够），`flex: 0 0 auto` |
| 会话列表 | `/chat` 单独一页 | 常驻左栏，可独立滚动 |
| 会话页路由 | `/chat/t/:kind/:id` | 同一路由即可，桌面下 `/chat` 显示 `chat-empty`（现有 `:170` 的「选择一个会话开始聊天」保留，文案改为「从左边挑一个会话，或者直接找个人搭句话」） |
| 高度 | `100dvh` | `height: calc(100vh - var(--ys-topbar-h))`；`100dvh` 在桌面无副作用但也没收益，统一用 `100dvh` 亦可 |
| 长按操作 | ActionSheet | 气泡 hover 时右侧浮现 20px 图标按钮（撤回 `RefreshLeft` / 引用 `Paperclip`） |
| 输入区 | 吸底 + 键盘位移 | 固定在消息区下方，不需要 `--kb-h` 逻辑 |
| 「我关注的」区块 | 已删 | **保留**（桌面侧栏有空间，且它是发起新会话的快捷入口） |
| 破冰额度条 | 列表页顶部 | 移到左栏底部常驻 |

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 会话列表全空：主句「还没有人找你说话」（`--ys-text-3xl`/600/`--ys-fg`）+ 副句「在圈子里发一条，或者去看看大家都在找什么」（13px `--ys-muted`）+ 主按钮「去逛逛」（`--ys-accent` 实底，高 44px，跳 `/circles`）。<br>陌生人分组空：**不单独出空态**，整个折叠分组隐藏（`v-if="strangerList.length"`，不是收起一个空的分组头）。 |
| **加载骨架屏** | 列表页：6 条会话骨架（44px 圆 + 两行文字条），微光 `--ys-ease-linear`。会话页：先渲染顶栏 + 来源上下文条的真实骨架（因为路由参数已知），消息区用 5 条气泡骨架（左 3 右 2 交替，宽度 40%/60%/52%）。**禁止全屏 spinner。** |
| **错误** | 单个发送失败：气泡保留为「未发出」态（底 `--ys-danger-soft`，左侧 16px `WarningFilled` 图标，色 `--ys-danger`），气泡下方 13px `--ys-danger` 文案「没发出去，点这里重试」，整条气泡可点重发。**草稿必须保留**——失败就清空输入框是最伤人的交互。<br>历史加载失败：消息区中部一行 `--ys-danger` 文字 + 「重新加载」按钮。 |
| **权限不足** | 三种，都要有具体文案，不能只弹一个 toast：<br>1) 对方关闭陌生人消息（`AC-05`）：输入区上方 13px `--ys-danger` 条「对方已关闭陌生人消息，先去 TA 主页看看别的」+ 输入区禁用（`--ys-surface-2` 底、`--ys-meta` 字）。<br>2) 当日额度用尽（`AC-04`）：主按钮 disabled，下方 13px `--ys-meta`「今天打招呼的次数用完了，明天再来」。**不弹错误弹窗**。<br>3) 命中敏感词（`AC-06`）：输入区上方 `--ys-warning-soft` 底 + `--ys-warning` 文字「这句话可能不太合适，改改再发」。 |
| **内容超长** | 昵称 1 行截断；预览 1 行截断；单条消息正文 `overflow-wrap: anywhere`（无空格长串不撑破气泡）；气泡最大宽 72%；未读角标 99+；超长单条消息（>500 字）气泡内最多显示 8 行 + 「展开全文」文字按钮。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 列表 → 会话页路由切换 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 只做 `opacity` + 12px `translateX`。禁止横向整页滑动转场（与 iOS 边缘返回手势冲突） |
| 键盘弹起 / 收起 | `--ys-motion-base` 200ms | `--ys-ease-out` | 动 `transform: translateY`，**不动 `height` / `bottom`** |
| 新消息进入 | `--ys-motion-base` 200ms | `--ys-ease-out` | `opacity` 0→1 + `translateY` 8px→0 |
| 消息列表滚动到底 | — | — | `scrollTop = scrollHeight`，**不加平滑动画**（平滑滚动在快速连发时会互相打断） |
| 长按气泡 → ActionSheet | `--ys-motion-slow` 280ms | `--ys-ease-in-out` | 见 `../mobile-shell.md` |
| 发送按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 分组展开/收起 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 动 `grid-template-rows`（0fr→1fr）或 `max-height`，**禁止**用 `CaretBottom` 旋转 + 高度跳变同时出现 |

- 禁止「对方正在输入…」的三点跳动动画（MOTION_INTENSITY = 3，属装饰动画）。
- 禁止消息发送成功时的气泡放大回弹。

---

## 5. 无障碍

- 消息列表 `role="log"` + `aria-live="polite"`，新消息到达时屏幕阅读器播报。
- 每个气泡是 `<li>`，我方气泡带 `aria-label="我发送的消息"`。
- 发送按钮纯图标时 `aria-label="发送"`；撤回按钮 `aria-label="撤回这条消息"`。
- 未读角标不能只靠红色：徽章内是数字（文本），且父元素 `aria-label="3 条未读"`。
- 输入区 `aria-label="消息输入框"`，Enter 发送 / Shift+Enter 换行要在页面上有一行可见提示（现有 placeholder 已写，保留）。
- 陌生人与私信分组头是 `<button aria-expanded="true|false">`。
- 触控热区：会话条目 72px（>44）、分组头 48px、发送按钮 44×44、气泡长按区 = 气泡本身（最小高 44px）。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-warning  --ys-warning-soft  --ys-danger  --ys-danger-soft
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md
--ys-lh-md  --ys-lh-base  --ys-ls-sm
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-8
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-slow  --ys-motion-page
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-aside-w  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（搭子专属）、`--ys-cat-*`（兴趣标签不在会话页出现）、`--ys-official-*`（官方通知不进私信）、`--ys-shadow-3`（ActionSheet 由 `mobile-shell.md` 管）。

---

## 7. 需要架构师配合的一点

`/chat/t/:kind/:id` 是**新增子路由**，需写进 `frontend/src/router/index.js` 的 `/chat` 之下：

```js
{ path: 'chat', name: 'chat', component: () => import('@/views/Chat.vue'), meta: {...} },
{ path: 'chat/t/:kind/:id', name: 'chatThread', component: () => import('@/views/Chat.vue'),
  meta: { requiresAuth: true, title: '会话', back: true, hideTabBar: true } },
```

移动端从列表页进会话页用 `router.push`，从会话页返回用顶栏 `ArrowLeft` 调 `router.back()`。桌面端两栏共存时，`/chat` 与 `/chat/t/...` 渲染同一组件的不同分支（`currentKind` / `currentConv` 现有状态保留即可，不需要大改）。

---

*本文件结束。*
