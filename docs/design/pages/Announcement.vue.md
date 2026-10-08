# Announcement.vue · 设计提示词（新增页面）

- **目标文件**：`frontend/src/views/Announcement.vue`（新增）
- **路由**：`/announcement`（`meta: { requiresAuth: true, title: '公告', back: true }`）
- **一句话目标**：校内官方公告一站触达——置顶优先、按分类筛、已读未读一眼可辨。
- **对应 Spec**：`SPEC.md §5.3`（`GET /api/v1/announcement?category=&cursor=&size=`、`GET /unread-count`）· `§7.3` 新增页 · `§9 AC-07 / AC-08`
- **对应 UIUX**：`UIUX.md §10.3`（权威感靠深墨徽章 + 来源署名，不靠彩色边框）

---

## 1. 移动端布局（<640px，默认基准）

垂直结构逐行：

| 行 | 区块 | 间距 / 尺寸 | 说明 |
|---|---|---|---|
| 1 | 顶栏 | 56px | 返回型：`ArrowLeft` + 「公告」+ 右 `Bell`（跳转到未读第一条） |
| 2 | 未读横条（仅未读数 > 0 时） | 高 44px，底 `--ys-accent-soft`，圆角 `--ys-radius-lg`，左右 `--ys-space-4`，下边距 `--ys-space-4` | 16px `Promotion` + 14px `--ys-accent`「还有 3 条没看」+ 右侧文字按钮「跳到第一条」 |
| 3 | 分类筛选条 | 高 44px，左右留白 0（横滑到边缘），下边距 `--ys-space-3` | 7 个 chip 横滑：全部 / 教务 / 后勤 / 活动 / 社团 / 紧急 / 其他。chip 高 32px（热区 44×44），圆角 `--ys-radius-full`。选中 = `--ys-accent` 底 + `--ys-accent-on` 字；未选 = `--ys-surface` 底 + 1px `--ys-border` + `--ys-fg-2` 字 |
| 4 | **置顶分组**（有置顶时） | 分组头 40px | 16px `Top` + 13px/500 `--ys-fg-2`「置顶」+ 计数。底 `--ys-surface-2` 通栏 |
| 4a | 置顶条目 | 见 §1.1 | 底 `--ys-official-surface` |
| 5 | 普通分组 | 分组头 40px，文案「全部公告」 | — |
| 5a | 普通条目 | 见 §1.1 | 已读底 `--ys-surface`，未读底 `--ys-surface`（同色）+ 左侧未读圆点 |
| 6 | 底部兜底 | `padding-bottom: calc(var(--ys-tabbar-h) + var(--ys-safe-bottom))` | 本页**保留 TabBar**（它是浏览型页面，不是任务型） |

### 1.1 公告条目结构（单列，`border-top: 1px solid var(--ys-border-soft)`）

```
第 1 行   来源徽章（深墨底白字 chip：--ys-official-badge-bg / --ys-official-badge-text，11px，--ys-radius-sm，最多 6 字）
          标题 15px/500/--ys-fg（未读）/ 15px/400/--ys-fg-2（已读），最多 2 行
          右侧：16px Top（仅置顶）+ 6px 未读圆点（--ys-danger，仅未读）
第 2 行   摘要 13px/--ys-muted，1 行截断
第 3 行   12px --ys-meta：发布时间（tabular-nums）+「· 已读 128 人」（仅管理员可见）
```

- 条目内边距 `var(--ys-space-4)`，最小高 72px（保证触控热区）。
- **「紧急」分类**（`category=5`）的额外处理：来源徽章右侧加一个 11px chip（`--ys-danger-soft` 底 + `--ys-danger` 字「紧急」）。**只用文字 + 浅底，不用彩色左边框**（红线 3）。
- 已读 / 未读的区分**不能只靠底色**：未读靠左侧 6px `--ys-danger` 圆点 + 标题字重 500；已读靠标题字重 400 + 无圆点。两者底色相同——因为底色差异会让整页看起来像斑马纹，破坏分区秩序。

### 1.2 置顶的视觉（AC-07）

置顶 = **分组 + 图钉图标 + 分组头文字「置顶」**，三重传达。置顶条目在列表最前直到 `top_expire_time`。置顶到期后自动降入普通分组，图钉消失——**不弹提示、不做动画**（静默降级，避免用户以为公告被删了）。

---

## 2. 桌面端布局（≥1024px）—— 只写差异

| 项 | 移动端 | 桌面端 |
|---|---|---|
| 结构 | 单列列表，点击跳详情页 | **两栏不跳页**：左 360px 列表 + 右详情预览区（`--ys-surface` + 1px `--ys-border` + `--ys-radius-xl`） |
| 选中态 | — | 条目底 `--ys-accent-soft` + 标题色 `--ys-accent`。**不用左侧色条** |
| 分类筛选 | 横滑 chip | 横排 chip 不横滑，放在列表上方 |
| 容器 | 全宽 16px | 左列 360px + 右列 `flex: 1`；≥1280px 总宽锁 `--ys-container-max` 1180px |
| hover | 无 | 条目底 `--ys-surface-2`，150ms `--ys-ease-out` |
| 未读横条 | 有 | 移到左列顶部，形态同 |

768–1023px：单列（同移动端），留白 24px，内容限宽 640px 居中。

---

## 3. 状态覆盖（五态）

| 态 | 设计 |
|---|---|
| **空态** | 1) 全库无公告：主句「还没有官方公告」（`--ys-text-2xl`/600）+ 副句「学校一发通知，这里就会第一时间出现」+ 次按钮「回首页」。<br>2) 某分类下无公告：主句「这个分类下还没有公告」+ 副句「换个分类看看，或者看全部」+ 主按钮「看全部」（清空分类筛选）。<br>3) 全部已读：不出空态，只把未读横条隐藏，列表正常展示（已读公告仍要能回看）。 |
| **加载骨架屏** | 未读横条骨架 1 条（44px）+ 分类 chip 骨架 7 个 + 6 条条目骨架（徽章块 48×18 + 两行文字条 + 一行 meta 条）。微光 `--ys-ease-linear`。骨架条目也要有置顶/普通两组分区形态（各 1 / 5 条）。 |
| **错误** | 列表加载失败：整页替换——13px `--ys-danger`「公告没拉出来，可能是网络断了」+ 主按钮「重试」。<br>未读数拉取失败：**静默**（隐藏未读横条，不报错）。未读数是增强信息。<br>分类筛选请求失败：保留上一次结果，chip 上方一行 13px `--ys-warning`「筛选没生效，展示的是全部公告」。 |
| **权限不足** | 公告列表对所有已登录用户可见。<br>唯一例外：未认证用户（`verify_status != 1`，`AC-11`）不能**发布**公告——本页是只读浏览页，不涉及。<br>若后端对未认证用户限制公告可见范围（架构师确认），则：整页替换——`Shield` 24px `--ys-muted` + `--ys-text-xl`「公告只对已认证的同学开放」+ 主按钮「去认证」。 |
| **内容超长** | 标题 2 行截断；摘要 1 行截断；来源机构名最多 6 字截断；未读数 99+ 封顶；已读人数 9999+ 封顶；时间 `tabular-nums`。 |

---

## 4. 交互与动效

| 交互 | 时长 | 缓动 | 说明 |
|---|---|---|---|
| 分类 chip 切换 | `--ys-motion-fast` 150ms | `--ys-ease-out` | chip 底色与文字色过渡；列表 `opacity` 淡入 |
| 条目点击进入详情 | `--ys-motion-page` 320ms | `--ys-ease-in-out` | 路由过渡（MainLayout 统一提供） |
| 桌面端选中条目 | `--ys-motion-fast` 150ms | `--ys-ease-out` | 底色 + 标题色 |
| 未读 → 已读（回详情页后返回） | `--ys-motion-base` 200ms | `--ys-ease-out` | 圆点 `opacity` → 0 + 标题字重从 500 变 400（**字重变化不加动画**，会造成重排） |
| 无限滚动追加 | — | — | 底部 3 条骨架，`rootMargin: 200px` |
| 按钮按下 | `--ys-motion-instant` 80ms | `--ys-ease-out` | `opacity: 0.7` |

- 禁止新公告到达时的「从顶部滑入 + 高亮闪烁」——`AC-08` 只要求推送到达，不要求视觉轰炸。到达时用 `role="status"` 播报 + 顶栏铃铛角标即可。
- 禁止置顶条目的任何持续动画（脉冲、呼吸灯）。

---

## 5. 无障碍

- 分类 chip `role="tablist"` / `role="tab"` + `aria-selected`。
- 未读圆点不能只靠红色：整条 `aria-label` 含「未读」，例如 `aria-label="未读公告：关于 2026 年春季选课的通知，来源 教务处"`。
- 置顶传达三重：图钉图标 + 分组头「置顶」文字 + 条目内图钉，屏幕阅读器读「置顶公告」。
- 「紧急」chip 就是文字「紧急」，不需要额外 `aria-label`。
- 条目整条是 `<a>` / `<button>`，不是 `div @click`。
- 触控热区：条目 ≥ 72px、chip 44×44、未读横条 44px。

---

## 6. Token 引用清单（本页穷举）

```
--ys-bg  --ys-surface  --ys-surface-2  --ys-border  --ys-border-soft
--ys-fg  --ys-fg-2  --ys-muted  --ys-meta
--ys-accent  --ys-accent-hover  --ys-accent-on  --ys-accent-soft
--ys-danger  --ys-danger-soft  --ys-warning  --ys-warning-soft
--ys-official-badge-bg  --ys-official-badge-text  --ys-official-surface
--ys-text-xs  --ys-text-sm  --ys-text-base  --ys-text-md  --ys-text-lg  --ys-text-2xl
--ys-lh-base  --ys-lh-md  --ys-ls-sm  --ys-ls-xl
--ys-weight-regular  --ys-weight-medium  --ys-weight-semibold
--ys-font-body  --ys-font-mono
--ys-space-1  --ys-space-2  --ys-space-3  --ys-space-4  --ys-space-6
--ys-radius-sm  --ys-radius-md  --ys-radius-lg  --ys-radius-xl  --ys-radius-full
--ys-shadow-2
--ys-motion-instant  --ys-motion-fast  --ys-motion-base  --ys-motion-page
--ys-ease-out  --ys-ease-in-out  --ys-ease-linear
--ys-focus-ring
--ys-container-max  --ys-content-col  --ys-gutter-phone  --ys-gutter-desktop
--ys-topbar-h  --ys-tabbar-h  --ys-safe-bottom  --ys-touch-min
--ys-z-sticky
```

**本页不使用**：`--ys-accent-2` 系（公告不是搭子邀约）、`--ys-cat-*`（公告分类用中性 chip，不用兴趣大类配色）、`--ys-success`（无成功态）、`--ys-shadow-1` / `--ys-shadow-3`、`--ys-motion-slow`（无抽屉）。

---

## 7. 与首页公告条的关系

首页 `Home.vue` 的公告条（见 `Home.vue.md §1.1`）与本页共用了**同一套条目视觉**（深墨徽章 + 标题 + 图钉）。差异只有两条：

1. 首页最多 2 条且可当天收起；本页是完整列表。
2. 首页条目高 64px 且带 `Close`；本页条目高 72px 无 `Close`。

**建议把公告条目抽成一个 `components/AnnouncementItem.vue` 组件**，两处复用，避免视觉漂移。这个组件不在 `SPEC.md §7.4` 的自研组件清单里，属设计侧追加建议（advisory）。

---

*本文件结束。*
