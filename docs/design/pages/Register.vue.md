# Register.vue · 设计提示词

- **目标文件**：`frontend/src/views/Register.vue`（约 200 行，改造档位：中，0.4 人日）
- **路由**：`/login/register`（`AuthLayout`）
- **一句话目标**：5 个字段在手机上压进一屏可滚动的单列表单，学号/昵称标为可选，注册成功后直接进引导。
- **对应 Spec**：`SPEC.md §7.1` · `§5.4`（F2 认证端点）· `§9 AC-18`（第 1 步不可跳过）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 品牌区 | 顶部 `var(--ys-space-12)`（48px），居中 | 「友声」30px/600/`--ys-fg` + 13px `--ys-muted`「嘤其鸣矣，求其友声」 |
| 2 | 表单区 | 上边距 `var(--ys-space-8)`，左右 `var(--ys-gutter-phone)` | 见 §1.1 |
| 3 | 底部链接 | 上边距 `var(--ys-space-6)`，居中 | 13px `--ys-muted`「已有账号？」+ 文字按钮「去登录」（`--ys-accent`，44×44 热区） |
| 4 | 开发模式提示 | 上边距 `var(--ys-space-4)`，居中 | 11px `--ys-meta`。**仅 `import.meta.env.DEV` 时渲染**，生产环境不出现（现有 `:45` 是无条件渲染的，需改） |
| 5 | 安全区 | `padding-bottom: calc(var(--ys-space-8) + var(--ys-safe-bottom))` | — |

### 1.1 表单字段（`label-position="top"`，字段间距 `--ys-space-5`）

| 序 | 字段 | 必填 | 规格 |
|---|---|---|---|
| 1 | 邮箱/手机号 | 必填 | `el-input` 高 44px，16px 字号。label「邮箱/手机号」，placeholder「you@qq.com 或 13800000000」 |
| 2 | 验证码 | 必填 | 一行两列：`flex: 1` 输入框（maxlength 6）+ 「获取验证码」按钮（最小宽 104px、高 44px）。placeholder「6位验证码」 |
| 3 | 密码 | 必填 | `type="password" show-password`，高 44px。placeholder「6~64 位」 |
| 4 | 学号 | **可选** | 高 44px。label 写「学号（选填）」——**必填标记的位置要说清可选**，现有 `:26` 已写「学号(可选)」，保留。下方 13px `--ys-meta` hint「填了能更快通过校内认证」 |
| 5 | 昵称 | **可选** | 高 44px。placeholder「不填就用账号」 |
| 6 | 注册按钮 | — | 全宽，高 44px，文案「注册并登录」 |

**字段顺序的理由**：验证码紧接邮箱（用户要按「填邮箱 → 点获取 → 填验证码」的顺序操作，把密码插在中间会打断这个动作链）。现有顺序（`:11-34`）已经是这个顺序，保持。

**5 个字段是否压得进一屏**：375×667 下，品牌区 100px + 5 字段（每个 label 20px + 输入 44px + 间距 20px = 84px，共 420px）+ 按钮 44px + 底部 80px ≈ 644px。**刚好一屏**，不需要滚动；更矮的机型（iPhone SE 568px）需要滚动，可接受。

### 1.2 背景与卡片

与 `Login.vue` 同：`Register.vue:148` 的 `linear-gradient` 必须删除，改 `background: var(--ys-bg)`；`.auth-card { width: 400px }` 改 `width: 100%; max-width: 400px`，移动端去卡片，桌面端保留。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 卡片 | 无 | `el-card` 400px 居中 + `--ys-shadow-2` |
| 品牌区 | 顶部 48px | 卡片内顶部，24px 字号 |
| 表单 | 左右 16px | 卡片内左右 24px |
| 验证码行 | 一行两列 | 同（桌面上也不改成三列） |

768–1023px：与桌面端同（卡片形态）。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 表单的空态是 placeholder。注册成功后**不显示空态**，直接 `router.push('/onboarding')`（见 §3 权限不足的说明）。 |
| **加载骨架屏** | 静态表单无异步数据，不做骨架屏。按钮 loading = spinner + 宽度锁定。 |
| **错误** | 1) 邮箱已被注册：字段下方 13px `--ys-danger`「这个邮箱已经注册过了，直接登录吧」+ 右侧内联文字按钮「去登录」。<br>2) 验证码错误/过期：验证码字段下方「验证码不对或者过期了，重新获取一次」。<br>3) 密码太短：密码字段下方「密码要 6 位以上」。<br>4) 网络失败：按钮下方「网络好像断了，点这里重试」，其中「点这里」可点。<br>**所有错误都贴近字段，禁止汇总到顶部、禁止只弹 toast。** |
| **权限不足** | 注册页本身无权限态。<br>注册成功后必须进 `/onboarding`（`AC-18`：第 1 步不可跳过，未填学校/学院/年级不能进 Feed）。现有 `Register.vue` 注册成功后跳转目标需确认是 `/onboarding` 而不是 `/` —— **这是本轮必须改的一处路由行为**，请前端核对。<br>邮箱域名不在校园白名单时：不阻止注册（用户可能用校外邮箱），只在学号 hint 处提示「校园邮箱能自动认证」。 |
| **内容超长** | 邮箱 64 字、密码 64 字、昵称 32 字、学号 32 字（`maxlength`）；错误文案不截断；品牌字不缩放。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 输入框聚焦 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 边框 → `--ys-accent` + `var(--ys-focus-ring)` |
| 「获取验证码」倒计时 | — | — | 按钮禁用 + 文案「60s 后重发」，`aria-live="polite"` 每 10 秒播报 |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 按钮 loading | — | — | spinner + 文案保持「注册并登录」 |
| 错误提示出现 | `--ys-motion-fast` 150ms | `--ys-ease-out` | `opacity` + `translateY(-4px)`，**禁止 shake** |
| 注册成功 → 引导页 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 路由过渡（由 MainLayout 统一提供） |

- 禁止注册成功的庆祝动画。
- 禁止字段逐个「飞入」的入场动画。

---

## 5. 无障碍

- 每个输入框有可见 label；**可选字段的 label 必须写明「选填」**（现有 `:26` `:29` 已有，保持）。
- 必填项同时有 `required` 属性与视觉标记（现有 rules 已配置 `required`）。
- 密码框眼睛按钮 `aria-label="显示密码"` / `aria-label="隐藏密码"`。
- 错误提示 `role="alert"`。
- 学号字段的 hint 用 `aria-describedby` 关联到输入框。
- 触控热区：输入框 44px、按钮 44px、眼睛按钮 44×44、底部「去登录」44×44。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on
--ys-danger  --ys-warning
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-3xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-3xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8  --ys-space-12
--ys-radius-md  --ys-radius-lg  --ys-radius-xl
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast
--ys-ease-out
--ys-focus-ring
--ys-gutter-phone  --ys-gutter-desktop  --ys-safe-bottom  --ys-touch-min
```

**本页不使用**：`--ys-accent-soft`、`--ys-accent-2` 系、`--ys-cat-*`、`--ys-official-*`、`--ys-success`、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-base` / `--ys-motion-slow`（路由过渡由 MainLayout 提供）。

---

## 7. 必须与 Login.vue 同步改的三处

1. `.auth-page` 的 `linear-gradient`（`Register.vue:148` / `Login.vue:171`）—— 两处都要删。
2. `.auth-card { width: 400px }` —— 两处都要改成 `width: 100%; max-width: 400px`。
3. 硬编码 `#f7f8fa` —— 两处都在渐变里，随渐变一起消失。

建议把 `.auth-*` 样式提到 `AuthLayout.vue` 里统一提供，避免第三次再改两遍。

---

*本文件结束。*
