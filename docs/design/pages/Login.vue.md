# Login.vue · 设计提示词

- **目标文件**：`frontend/src/views/Login.vue`（213 行，改造档位：中，0.4 人日）
- **路由**：`/login/form`（`AuthLayout`）
- **一句话目标**：删掉渐变背景与 400px 固定宽卡片，改成手机上「纯底色 + 内容撑满留白」的单列表单。
- **对应 Spec**：`SPEC.md §7.1` · `§8.1` 红线（无 AI 模板味）· `§10.3` 第 6 条（禁止空洞占位文案）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 品牌区 | 顶部 `var(--ys-space-16)`（64px），居中 | 「友声」品牌字 30px/600/`--ys-fg`，字距 `--ys-ls-3xl`。下方 13px `--ys-muted`「嘤其鸣矣，求其友声」 |
| 2 | Tab 条 | 上边距 `var(--ys-space-8)`，高 44px | 自绘分段：「密码登录」「邮箱验证码」两项。选中 = `--ys-accent` 文字 + 底部 2px 指示条 |
| 3 | 表单区 | 上边距 `var(--ys-space-6)`，左右 `var(--ys-gutter-phone)` 16px | 见 §1.1 |
| 4 | 底部链接 | 上边距 `var(--ys-space-8)`，居中 | 13px `--ys-muted`「没有账号？」+ 文字按钮「去注册」（`--ys-accent`，热区 44×44） |
| 5 | 安全区 | `padding-bottom: calc(var(--ys-space-8) + var(--ys-safe-bottom))` | — |

### 1.1 表单字段（`label-position="top"`）

| 字段 | 组件 | 规格 |
|---|---|---|
| 邮箱/手机号 | `el-input` | 高 44px，`--ys-text-md` 16px（**防 iOS 聚焦缩放**），label 13px/500 `--ys-fg-2`，placeholder「you@qq.com 或 13800000000」（保留，是真实示例） |
| 密码 | `el-input type="password" show-password` | 同上。右侧眼睛图标热区扩到 44×44 |
| 验证码（code Tab） | `el-input` + 「获取验证码」按钮 | 一行两列：`flex: 1` 输入 + 按钮最小宽 104px、高 44px。倒计时文案保留「60s 后重发」 |
| 登录按钮 | `el-button type="primary"` | **全宽，高 44px**，圆角 `--ys-radius-lg` |

- 字段间距 `var(--ys-space-5)` = 20px。
- 错误提示：字段下方 13px `--ys-danger`，**贴近字段**（现有 `el-form-item` 已如此，保持）。
- 表单顶部**不放**「登录」大标题——品牌区已经承担了这个角色，再来一个标题是重复。

### 1.2 背景改造（本页必须删的一项）

现有 `Login.vue:171`：

```css
background: linear-gradient(135deg, var(--el-color-primary-light-8), var(--app-bg, #f7f8fa) 55%, var(--el-color-primary-light-9));
```

**必须删除**，改为 `background: var(--ys-bg)`。三条理由：

1. 它是「AI 模板味」的典型：登录页给一个 135° 渐变背景，是这个十年被复制最多的登录页套路。
2. `#f7f8fa` 是硬编码色值，命中红线 2。
3. 渐变在移动端低端安卓机上是合成层开销，与 `AC-17` 的 LCP < 2.0s 预算冲突——登录页是首屏，每一毫秒都算。

**卡片也要改**：`.auth-card { width: 400px }` 在 375px 下横向溢出（400 > 375 - 32）。改成 `width: 100%; max-width: 400px`，且移动端**去掉 `el-card`**，直接把表单放在 `--ys-bg` 上（登录页不需要卡片容器，留白已经足够分组）。桌面端保留卡片（`max-width: 400px` 居中 + `--ys-shadow-2`）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 背景 | `--ys-bg` | 同（不加渐变） |
| 卡片 | 无 | `el-card`，`width: 400px` 居中，圆角 `--ys-radius-xl`，`--ys-shadow-2`。`min-height: 100vh` 居中布局（现有 `:167-172` 保留，去掉 gradient） |
| 品牌区 | 顶部 64px | 卡片内顶部，字号 24px |
| 表单 | 左右 16px | 卡片内左右 `--ys-space-6` 24px |
| 底部链接 | 页面底部 | 卡片内底部 |

768–1023px：与桌面端同（卡片形态），留白 24px。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 表单的空态是 placeholder，不是空状态区块。两个 Tab 切换时**保留已填内容**（切换 Tab 不该清空邮箱）。 |
| **加载骨架屏** | 登录按钮 loading = spinner + **宽度锁定**（全宽按钮无此问题）。**不做骨架屏**——静态表单没有异步数据。 |
| **错误** | 三类，都贴近字段：<br>1) 格式错误：字段下方 13px `--ys-danger`「邮箱格式不正确」「验证码为 6 位数字」（现有 rules 已配置）。<br>2) 账号密码错误：登录按钮下方 13px `--ys-danger`「邮箱或密码不对，再试一次」。**不要**弹 `ElMessage`——弹窗挡住键盘，用户还得再点一次才能改。<br>3) 网络失败：同上位置「网络好像断了，点这里重试」，其中「点这里」是可点文字按钮，直接重发上一次请求。 |
| **权限不足** | 登录页本身无权限态。唯一的「拦路」场景：`returnUrl` 指向管理员页但当前账号非管理员——登录成功后由 `router/index.js:41-45` 守卫拦下并提示「需要管理员权限」，本页不处理。<br>账号被封禁（F4 处置结果）：登录返回封禁错误 → 整页替换——`--ys-text-xl` 主句「这个账号被暂停使用了」+ 13px 副句「如果你觉得是误判，联系管理员申诉」+ 次按钮「返回登录」。**必须给出申诉路径**，不能只说「账号异常」。 |
| **内容超长** | 邮箱最长 64 字、密码最长 64 字（`maxlength`）；错误文案不截断（`overflow-wrap: anywhere`）；品牌字不缩放。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| Tab 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 指示条 `transform: translateX` |
| 输入框聚焦 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 边框 → `--ys-accent` + `var(--ys-focus-ring)` |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7`（禁用态用 `--ys-surface-2` 底 + `--ys-meta` 字） |
| 按钮 loading | — | — | spinner + 文案保持「登录」 |
| 错误提示出现 | `--ys-motion-fast` 150ms | `--ys-ease-out` | `opacity` + `translateY(-4px)`（**不抖动**，禁止 shake 动画） |
| 键盘弹起 | — | — | 页面自然滚动，输入区不做特殊位移（表单页内容短，系统滚动足够） |

- 禁止输入错误时的左右抖动（shake）动画。
- 禁止登录成功的转场动画（直接跳 `returnUrl`）。

---

## 5. 无障碍

- 每个输入框有**可见 label**（现有 `el-form-item label` 已有，保持），不能用 placeholder 当 label。
- 密码框 `show-password` 的眼睛按钮 `aria-label="显示密码"` / `aria-label="隐藏密码"`。
- 验证码倒计时按钮 `aria-live="polite"`（每 10 秒播报一次即可，不要每秒播报）。
- 「获取验证码」按钮禁用时 `disabled` 属性 + `aria-disabled`。
- 错误提示 `role="alert"`，出现即播报。
- 触控热区：输入框 44px、按钮 44px、眼睛按钮 44×44、底部「去注册」44×44。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on
--ys-danger  --ys-warning
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-xl  --ys-text-2xl  --ys-text-3xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-3xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8  --ys-space-16
--ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast
--ys-ease-out
--ys-focus-ring
--ys-gutter-phone  --ys-gutter-desktop  --ys-safe-bottom  --ys-touch-min
```

**本页不使用**：`--ys-accent-soft`（登录页无浅底提示条）、`--ys-accent-2` 系、`--ys-cat-*`、`--ys-official-*`、`--ys-success`（登录成功直接跳转，无成功态）、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-base` 以外更长的动效。

---

## 7. 与 Register.vue 的一致性问题

`Login.vue` 与 `Register.vue` 共用 `.auth-page` / `.auth-card` / `.auth-title` / `.auth-link` 四个类名，且都有同样的渐变背景问题（`Register.vue:148`）。**两页必须同时改**，并建议把 `.auth-*` 样式提到 `AuthLayout.vue` 或一份共享 CSS 里，避免两页各自为政。

---

*本文件结束。*
