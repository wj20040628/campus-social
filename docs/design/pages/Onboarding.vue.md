# Onboarding.vue · 设计提示词（新增页面）

- **目标文件**：`frontend/src/views/Onboarding.vue`（新增）
- **路由**：`/onboarding`（`meta: { requiresAuth: true, title: '完善资料', back: false, hideTabBar: true }`）
- **一句话目标**：注册后 3 步引导 —— 选学校/学院/年级（**不可跳过**）→ 选 3-5 个兴趣标签 → 关注推荐的人与圈子。
- **对应 Spec**：`SPEC.md §5.5`（`/onboarding/tags`、`/onboarding/complete`、`/onboarding/recommend-users`、`/onboarding/recommend-circles`）· `§7.3` 新增页 · `§9 AC-18 / AC-19`
- **注意**：`meta.back = false` —— 引导页不允许系统返回键退出第 1 步（AC-18）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行（三步共用同一外壳）：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 进度条 | 高 4px，通栏 | 底 `--ys-surface-2`，填充 `--ys-accent`，宽度 = 当前步 / 3。`--ys-motion-base` 200ms `--ys-ease-out` 过渡 |
| 2 | 步骤指示 | 高 44px，左右 `--ys-space-4` | 左侧 13px `--ys-meta`「第 1 步 / 共 3 步」；右侧文字按钮「跳过」（**仅第 2、3 步渲染**，见 §1.4） |
| 3 | 标题区 | 左右 `--ys-space-4`，上边距 `--ys-space-6`，下边距 `--ys-space-2` | 见各步 |
| 4 | 内容区 | `flex: 1`，左右 `--ys-space-4`，可滚动 | 见各步 |
| 5 | **吸底操作栏** | 高 56px + `padding-bottom: var(--ys-safe-bottom)`，上边框 1px `--ys-border`，底 `--ys-surface` | 见各步 |

### 1.1 第 1 步：选学校 / 学院 / 年级（不可跳过，AC-18）

```
标题   24px/600/--ys-fg「先说清楚你是哪个学校的」
副标题 13px --ys-muted「认证之后才能发动态和搭话」

字段（label-position="top"，间距 --ys-space-5）：
  学校  el-select 高 44px（本轮单校，默认值已填好，可改）
  学院  el-select 高 44px（选项来自后端字典）
  年级  el-select 高 44px（2022-2026 级，共 5 项）

底部   主按钮「下一步」全宽 44px，三项都填了才可点
```

- **没有「跳过」按钮**（AC-18 硬性要求）。
- 顶栏不提供返回；安卓物理返回键 / iOS 边缘返回手势：拦截并提示「先选完学校和年级，就一步」——**不能直接退出引导**。
- 三个下拉在 375px 下各占满一行（不并排）。

### 1.2 第 2 步：选 3-5 个兴趣标签

```
标题   24px/600「选几个你感兴趣的」
副标题 13px --ys-muted「选 3 到 5 个，之后找搭子就是按这个匹配的」

内容   标签云：chips 自动换行，--ys-space-2 间隔
       chip 高 32px（热区 44×44），圆角 --ys-radius-full
       未选：--ys-surface 底 + 1px --ys-border + --ys-fg-2 字
       选中：--ys-accent-soft 底 + 1px --ys-accent + --ys-accent 字 + 16px Check
       标签按 category 分组，每组上方 13px/500 --ys-fg-2 组标题
       （运动 / 游戏 / 学习 / 社团 / 生活，共 ≤60 个，来自 /onboarding/tags）

底部   左：13px --ys-meta「已选 3 个（至少 3 个）」
       右：主按钮「下一步」（选满 3 个才可点；选到第 6 个时最旧的自动取消并 toast「最多 5 个，已经帮你去掉一个」）
```

- 选满 5 个后，未选 chip 变禁用态（`--ys-surface-2` 底 + `--ys-meta` 字），**但不隐藏**——隐藏会让用户以为标签变少了。
- 分组标题用 `--ys-cat-*` 配色吗？**不用**。`--ys-cat-*` 是「找搭子列表里的标签」专用；这里是「选择界面」，选中态用 `--ys-accent-soft` 更清晰，而且避免了四套颜色同时出现导致的视觉噪音。

### 1.3 第 3 步：关注推荐的人与圈子

```
标题   24px/600「关注几个同学和圈子」
副标题 13px --ys-muted「关注之后首页就有内容了，随时可以取消」

内容   两个分组：
      「推荐同学」——单列，每行 64px：
        左 44px 头像 + 中 昵称 14px/500 + 13px --ys-meta「计算机学院 · 大三 · 3 个共同标签」
        右 「关注」胶囊按钮（--ys-accent 描边 + --ys-accent 字，44px 高，最小宽 72px）
        已关注：--ys-surface-2 底 + --ys-fg-2 字「已关注」+ 16px CircleCheck

      「推荐圈子」——单列，每行 64px：
        左 48px 圆角方形头像 + 中 圈子名 14px/500 + 13px --ys-meta「128 成员」
        右 同样的「加入 / 已加入」按钮

底部   左：13px --ys-meta「已关注 2 人 · 已加入 1 个圈子」
       右：主按钮「进友声」（全宽 44px）
```

- 两个分组各显示 **5 条**（不是全量——引导页的选项是「给个起点」，不是「让你挑完」）。
- 底部主按钮文案是「进友声」，不是「完成」。理由：这一步的语义是「你可以开始用了」，不是「你提交了一份表单」。

### 1.4 「跳过」按钮的三条规则

| 步 | 跳过按钮 | 理由 |
|---|---|---|
| 第 1 步 | **不渲染** | `AC-18`：未填学校/学院/年级不得进入 Feed |
| 第 2 步 | 渲染，文案「以后再选」 | 标签是增强信息，可后补。点击后直接进入第 3 步（不是退出引导） |
| 第 3 步 | 渲染，文案「先逛逛」 | 关注是增强信息。点击后直接完成引导 |

跳过第 2 步时，`/onboarding/complete` 仍要被调用（带着第 1 步的数据 + 空标签数组），否则用户每次进首页都会被重定向回引导页。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 容器 | 全屏 flex 列 | 居中卡片：`max-width: 480px`，`--ys-surface` 底 + 1px `--ys-border` + `--ys-radius-xl` + `--ys-shadow-2`，垂直居中于视口 |
| 操作栏 | 吸底 | 卡片内底部，**不吸底** |
| 标签云 | 单列自动换行 | 同（480px 宽下换行更舒适） |
| 第 3 步列表 | 单列 | 同 |
| 背景 | `--ys-bg` | `--ys-bg` + 顶部居中品牌字「友声」30px（桌面有空间放品牌） |

768–1023px：与桌面端同（居中卡片 480px）。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 1) 第 3 步推荐列表为空（冷启动，无用户可推）：<br>　　「推荐同学」分组替换为主句「现在还没有同学可以推荐」（`--ys-text-lg`/600）+ 副句「等你关注几个同学，这里就会有人了」——**整个分组降级为一行文字，不占 5 条的高度**。<br>　　「推荐圈子」为空时同理。<br>　　两个分组**同时**为空时（真正的冷启动）：内容区替换为主句「你是前 100 个用户之一」（`--ys-text-2xl`/600）+ 副句「现在人还少，先去发一条，后面来的人就能看到你」+ 主按钮「进友声」。<br>　　**禁止出现空白页**（`AC-19`）。<br>2) 第 2 步标签列表为空：整步跳过（自动进第 3 步），不出空态。 |
| **加载骨架屏** | 第 1 步：静态表单，无骨架（选项来自字典，随页面一起加载）。<br>第 2 步：标签云骨架（12 个 chip 块，宽 60-96px 随机），微光 `--ys-ease-linear`。<br>第 3 步：5 条列表骨架（44px 圆 + 两行条 + 右侧按钮块）。 |
| **错误** | 任一字段提交失败：操作栏上方浮出 13px `--ys-danger`「没提交成功，再点一次」，**已选内容全部保留**（引导页丢数据是最伤的）。<br>标签拉取失败：内容区替换 13px `--ys-danger`「标签没拉出来」+ 主按钮「重试」+ 次按钮「以后再选」（走跳过路径）。<br>推荐列表拉取失败：两个分组各自降级为一行 13px `--ys-meta`「推荐没拉出来，不影响你进友声」，底部按钮仍可点。**不阻塞引导完成**。 |
| **权限不足** | 引导页对已登录未引导用户可见。<br>**已完成引导的用户**访问 `/onboarding`：重定向到 `/`（不是报错）。<br>未登录访问：由 `router/index.js` 的 `requiresAuth` 守卫处理，本页不管。<br>第 1 步未完成就试图访问 `/`：由路由守卫拦回 `/onboarding`（`AC-18` 的执行点在守卫，不在本页）。 |
| **内容超长** | 标签名最多 6 字截断；昵称 1 行截断；圈子名 1 行截断；学院名 1 行截断；「3 个共同标签」这类描述 1 行截断；已选计数 99+ 不适用（上限 5）。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 步骤切换 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 旧步骤 `translateX(-24px)` + `opacity` → 0，新步骤从 `translateX(24px)` 淡入。**禁止纵向位移**（会让进度条与内容错位） |
| 进度条填充 | `--ys-motion-base` 200ms | `--ys-ease-out` | 只动 `width`（本处是唯一允许动 `width` 的地方——进度条语义就是宽度，且无子元素重排） |
| 标签选中 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色 + 边框 + `Check` 图标淡入。**禁止缩放弹跳** |
| 关注按钮状态切换 | `--ys-motion-base` 200ms | `--ys-ease-out` | 文字与底色交叉淡入，**宽度锁定** |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |
| 完成 → 首页 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 路由过渡（MainLayout 提供） |

- 禁止引导页的任何插画/吉祥物/庆祝动画（`UIUX.md §2.3`：不做「可爱校园 App」感）。
- 禁止步骤切换时的横向整页滑动转场（与浏览器返回手势冲突，且本页要拦截返回手势）。

---

## 5. 无障碍

- 进度条 `role="progressbar"` + `aria-valuenow="1"` + `aria-valuemin="1"` + `aria-valuemax="3"` + `aria-label="引导进度"`。
- 步骤指示「第 1 步 / 共 3 步」用 `aria-live="polite"` 播报。
- 标签 chip 是切换按钮：`role="checkbox"` + `aria-checked`（多选语义），或 `<button aria-pressed>`。**不要用 `role="button"` 让屏幕阅读器读成「按钮」**。
- 关注按钮 `aria-pressed` + `aria-label` 含对象名。
- 第 1 步三个下拉都有可见 label，必填标记同时有 `required` 属性。
- 「跳过」按钮 `aria-label` 要说明后果：「跳过兴趣标签，以后再选」。
- 触控热区：下拉 44px、标签 chip 44×44、列表行 64px、操作栏按钮 44px。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft  --ys-border-control
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-active  --ys-accent-on  --ys-accent-soft
--ys-success  --ys-danger  --ys-danger-soft
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-lg  --ys-ls-2xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-5  --ys-space-6  --ys-space-8
--ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-page
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（引导页不出现搭子邀约）、`--ys-cat-*`（标签选择用 `--ys-accent-soft`，不用四大类配色，见 §1.2 说明）、`--ys-official-*`、`--ys-warning` / `--ys-warning-soft`（无警告态）、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-slow`（无抽屉）、`--ys-topbar-h` / `--ys-tabbar-h`（本页 hideTabBar 且顶栏由 MainLayout 提供）。

---

## 7. 前端最容易做错的一点

**`/onboarding/complete` 必须在第 1 步之后每一步都能被调用**，且是**幂等**的（`SPEC.md §5.5` 已写明）。

容易犯的错：只在第 3 步点「进友声」时才调 `complete`。后果是——用户在第 2 步点了「以后再选」、然后杀掉 App，后端没有 `complete` 记录，`verify_status` 也没写，下次进首页又被守卫拦回引导页，陷入循环。

正确做法：
- 第 1 步「下一步」 → 调 `complete`（带学校/学院/年级，标签为空）
- 第 2 步「下一步」 → 调 `complete`（带前两步数据）
- 第 2 步「以后再选」 → 调 `complete`（标签为空数组）
- 第 3 步「进友声」/「先逛逛」 → 调 `complete`（带全部数据）

每步都调，后端幂等覆盖。前端不用记「我调过没有」。

---

*本文件结束。*
