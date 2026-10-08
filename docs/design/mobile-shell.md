# 移动端外壳规格

> 依据：`docs/SPEC.md` §7.4 + `docs/design/design-tokens.json` + `docs/design/pages/MainLayout.vue.md`
> 三个组件合计约 200 行代码，**不引入任何第三方移动端组件库**（ADR：否决 Vant 的决定性理由是它用 `--van-*` 变量体系，会把 ADR-009 刚裁掉的「Token 双源」在组件层原样重演）
> 断点一律用 `@media (--ys-bp-*)`，**禁止使用 Element Plus 的 `:xs/:sm/:md/:lg/:xl`**（EP 内置断点 768/992/1200/1920 与本项目640/768/1024/1280/1536 完全错位）

---

## 1. SafeArea.vue —— 安全区垫片

### 1.1 职责

处理 iPhone 底部 Home Indicator 与 Android 手势条。**只做垫片，不做布局。**

### 1.2 实现

```vue
<template>
  <div class="safe-area" :class="[`safe-area--${position}`]" aria-hidden="true" />
</template>

<script setup>
defineProps({
  position: { type: String, default: 'bottom', validator: v => ['top', 'bottom'].includes(v) },
})
</script>

<style scoped>
.safe-area { flex: none; pointer-events: none; }
.safe-area--bottom { height: var(--ys-safe-bottom); }
.safe-area--top { height: env(safe-area-inset-top, 0px); }
</style>
```

Token：`--ys-safe-bottom` = `env(safe-area-inset-bottom, 0px)`（`design-tokens.css:170`）

### 1.3 关键约束

| 约束 | 原因 |
|---|---|
| 高度用 `padding-bottom` 而非 `height` | 部分 Android WebView 下`env()` 返回 0 但实际手势区更高，用 `max()` 兜底 |
| 必须放在**滚动容器之外** | 放在滚动容器内，iOS 键盘弹起时安全区会跟着跳动 |
| `pointer-events: none` + `aria-hidden` | 纯装饰，不该被读屏朗读，也不该拦截点击 |
| 桌面端渲染为 0 高度 | `env()` 在桌面浏览器返回 0，无需额外媒体查询 |

### 1.4 必须一起处理的坑

`Chat.vue` 现有代码用 `height: calc(100vh - 60px)`（`:695`）。**移动浏览器 `100vh` 不含地址栏回缩高度**，会导致输入区被浏览器底栏遮住。改造时必须改用：

```css
height: calc(100dvh - var(--ys-topbar-h));
```

`dvh` 是动态视口高度，随地址栏收放实时变化。这条对应 SPEC §11坑 4 的同类问题。

---

## 2. TabBar.vue —— 移动端底部主导航

### 2.1 尺寸与结构

| 项 | 值 | Token |
|---|---|---|
| 高度 | **56px**（不含安全区） | `--ys-tabbar-h` |
| 单项热区 | 56×56px | `touch.tabbarItem` |
| 图标 | 20px | §1 尺寸档位 |
| 文字 | 13px | `--ys-fontSize-sm` |
| 图文间距 | 2px | 视觉紧贴，不额外加间距 token |
| 底部安全区 | `env(safe-area-inset-bottom)` | `--ys-safe-bottom` |

### 2.2 五个入口（PRD F1 锁定，不可增减）

| 顺序 | 语义 | 路由 | EP 图标 | 未读角标 |
|---|---|---|---|---|
| 1 | 首页 | `/` | `House` | 无 |
| 2 | 圈子 | `/circles` | `ChatDotSquare` | 无 |
| 3 | 发布 | `/publish` | `Plus`（凸起） | 无 |
| 4 | 消息 | `/chat` | `ChatDotRound` | **有**（纸条未回应数） |
| 5 | 我的 | `/settings` | `User` | 无 |

> 第 4 项的角标语义是**待回应的搭话纸条数**，不是私信未读数。纸条 24 小时未回应即消失，角标随之清零——这与普通未读消息「永久累积」的语义不同，不要混。

### 2.3 布局实现

```vue
<template>
  <nav class="tabbar" :class="{ 'tabbar--raised': isIOS }">
    <SafeArea position="bottom" />
    <ul class="tabbar__list">
      <li v-for="item in items" :key="item.path" class="tabbar__item">
        <RouterLink :to="item.path" class="tabbar__link" :aria-current="isActive(item.path) ? 'page' : undefined">
          <span class="tabbar__icon">
            <component :is="item.icon" />
            <UnreadDot v-if="item.badge" :count="item.badge" />
          </span>
          <span class="tabbar__label">{{ item.label }}</span>
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>
```

```css
.tabbar {
  position: fixed;
  left: 0; right: 0; bottom: 0;
  background: var(--ys-surface);
  border-top: 1px solid var(--ys-border);
  z-index: var(--ys-z-sticky);
}
.tabbar__list { display: flex; }
.tabbar__item { flex: 1 1 0; min-width: 0; }
.tabbar__link {
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  min-height: var(--ys-tabbar-h);
  gap: 2px;
  color: var(--ys-muted);
  text-decoration: none;
}
.tabbar__link[aria-current="page"] { color: var(--ys-accent); }
.tabbar__label { font-size: var(--ys-fontSize-sm); line-height: 1.2; }
```

### 2.4 中间项凸起（发布）

```css
.tabbar__item:nth-child(3) .tabbar__icon {
  width: 44px; height: 44px;
  border-radius: var(--ys-radius-full);
  background: var(--ys-accent);
  color: var(--ys-accent-on);
  display: grid; place-items: center;
  transform: translateY(-8px);
  box-shadow: var(--ys-shadow-2);
}
```

**必须用 `transform` 而不是改TabBar 高度**。改高度会把顶栏顶穿，且影响所有页面。

### 2.5 选中态反馈

- 颜色切换：`--ys-muted` → `--ys-accent`，`--ys-motion-duration-fast` 150ms + `--ys-motion-easing-out`
- **禁止弹跳**（`cubic-bezier` 任何控制点不得 `y<0` 或 `y>1`）
- 选中态**不改变图标尺寸**（会引发相邻项抖动）。要强调靠颜色 + 字重（`--ys-fontWeight-semibold`）
- 图标切换用同一路径的 fill 变化，不换图标（见 `icons-req.md`）

### 2.6 无障碍

- `<nav>` + `<ul>` + `<li>` 语义结构
- 当前项用 `aria-current="page"`，**不用纯颜色区分**
- 每项 `aria-label` 提供完整语义（「发布动态」而不是「发布」）
- 触控热区 56px 远超 44px 下限（AC-15）

### 2.7 与桌面侧的切换（写在 MainLayout，不写在 TabBar）

```css
/* 默认手机：显示 TabBar，隐藏侧栏 */
.tabbar { display: flex; }
.el-aside { display: none; }

@media (--ys-bp-lg) {   /* 1024px 起 */
  .tabbar { display: none; }
  .el-aside { display: block; }  /* 保留 220px el-menu 侧栏 */
}
```

**桌面侧栏保留 `el-menu` 不动**——不需要重写菜单，只是把它限制在 `--ys-bp-lg` 以上。`MainLayout.vue` 里新增 TabBar 不等于删除 `el-menu`。

---

## 3. ActionSheet.vue —— 底部操作表

### 3.1 用途

三处：

1. `Chat.vue` 气泡**长按**菜单（撤回 / 引用 / 举报）—— 常驻图标按钮会让每条消息右侧多两个 44px 热区，在 375px 下挤压正文宽度
2. `Home.vue` Feed 卡片「不感兴趣」
3. 通用危险操作确认（注销、退出圈子）

### 3.2 结构与动效

| 项 | 值 |
|---|---|
| 出现 | 从底部升起，`--ys-motion-duration-base` 200ms + `--ys-motion-easing-out` |
| 消失 | `--ys-motion-duration-fast` 150ms + `--ys-motion-easing-inOut` |
| 遮罩 | `--ys-z-overlay` 1200，`rgba(16, 33, 46, 0.45)` |
| 面板 | `--ys-surface`，圆角 `--ys-radius-2xl` 16px **仅顶部两角** |
| 项高 | ≥44px（`--ys-touch-min`） |
| 底部 | SafeArea 垫片 |

```css
.asheet__panel {
  border-radius: var(--ys-radius-2xl) var(--ys-radius-2xl) 0 0;
  padding-bottom: var(--ys-safe-bottom);
}
.asheet__item {
  min-height: var(--ys-touch-min);
  font-size: var(--ys-fontSize-base);
}
.asheet__item--danger { color: var(--ys-accent-2); }
```

### 3.3 危险操作样式

`--ys-accent-2` = `#B44A12`（暖橙红），**不用纯红**。原因：纯红 `#F56C6C` 读起来像「错误」，而「删除」「举报」是用户主动选择的动作，不是系统错误。

### 3.4 取消区

固定在最底部，与操作项之间有 1px `--ys-border` 分隔。文案「取消」用 `--ys-muted`。

**取消必须永远存在且永远在最下**——这是操作表能被安全使用的底线。

### 3.5 错误用法

|禁止 | 原因 |
|---|---|
| 用 ActionSheet 承载多步表单 | 需要输入的场景用全屏 Modal 或独立页（如 `CircleDetail` 发活动） |
| 超过 5 个项 | 超过就该用独立页面 |
| 底部弹出多个 ActionSheet | 会造成 z-index 打架与误触 |
| 点击遮罩不关闭 | 遮罩点击必须关闭，且必须同时允许弹层内滚动 |

---

## 4. 交付物与验收

| 文件 | 行数预估 |
|---|---|
| `frontend/src/components/mobile/SafeArea.vue` | ~20 |
| `frontend/src/components/mobile/TabBar.vue` | ~120 |
| `frontend/src/components/mobile/ActionSheet.vue` | ~80 |

### 验收（对应 SPEC §9）

- AC-15：375px 视口下 TabBar 五项热区均 ≥44×44px，无横向滚动
- AC-16：`Chat.vue` 移动端呈单栏，输入区不被 iOS 地址栏或 Home Indicator 遮挡
- AC-17：首屏 JS gzip < 300KB（三个组件合计不得引入新依赖）
- TabBar 在 iOS Safari 与 Android Chrome 底部均无遮挡；Android 手势条区域可点击

### 机器检查命令

```bash
# 1. 禁止引入第二套图标库/移动端库
rg -n "from ['\"](vant|lucide|@iconify)" frontend/src/ # 必须零命中

# 2. 禁止使用 EP 响应式属性（断点错位）
rg -n ":xs=|:sm=|:md=|:lg=|:xl=" frontend/src/views/ frontend/src/layouts/ # 必须零命中

# 3. 禁止硬编码色值
rg -n "#[0-9a-fA-F]{3,6}" frontend/src/components/mobile/ # 必须零命中

# 4. 禁止 emoji 图标
rg -n "[\x{1F300}-\x{1F9FF}\x{2600}-\x{26FF}\x{2700}-\x{27BF}]" frontend/src/ # 必须零命中

# 5. 禁止弹跳缓动
rg -n "cubic-bezier\([^)]*(0\.68|-0\.55)" frontend/src/ # 必须零命中
```