# SPEC §12.5 / §12.6 实测报告（frontend-2）

> 本文件是 §12.5 与 §12.6 的**实测**记录，全部数字来自真实命令输出。
> 与前一份《对齐报告-frontend-2.md》的区别：那份是设计对齐，这份只管测。

---

## 一、SPEC §12.5 前端构建

### 1.1 首屏 JS gzip（AC-17 预算 300KB）

```
assets/index-CKXEMRCm.js   raw 242385   gzip 81082
assets/vue-XHsQmJPl.js     raw 172323   gzip 66026
合计 147,108 字节 = 143.7 KB
预算 300KB(307200 字节),余量 156.3 KB
```

**AC-17 通过。** team-lead 独立复算 146,846 字节（差 0.3KB 是 gzip 压缩级别差异）。

### 1.2 element-plus 未整包打入（AC-17 第二条）

- dist 里 EP 拆成 **21 个 `el-*` chunk**，最大的 `el-drawer` 12K
- 若整包内联，单 chunk 会在 1MB 量级；实测最大 EP chunk 是 12K
- `grep "from 'element-plus'"` 代码零命中（唯一命中是 `utils/feedback.js:17` 的注释文字）
- 收口点 `utils/feedback.js` 已是按需引入：
  `element-plus/es/components/message/index.mjs` + `message-box/index.mjs`

**AC-17 通过。**

### 1.3 dist 产物数：97

```
干净构建（手工清空 dist 后）: 97 个文件（js 57 + css 40）
index.html 引用 3 个: index-*.js / vue-*.js / index-*.css
```

其余 94 个是**路由懒加载 chunk**（由 JS 动态 import），**不是死产物**。

> 我第一版把「不在 index.html 里」判为死产物，判错了 —— 懒加载的 chunk 本来就不在 index.html 里。

### 1.4 `clean()` 的残留问题（team-lead 修法，已复验）

**我上一轮报「97 个、无死产物」时，dist 里实际是 148 个** —— 三个批次的产物并存，
`index-*.js` 有三个同尺寸不同 hash 的副本。根因在循环逻辑：

```js
for (let round = 0; round < 100; round++) {
  const files = walk(OUT)        // 每轮重新取全量
  for (const f of files.slice(0, BATCH)) {   // 每轮都切最前面 40 个
    try { unlinkSync(f) } catch { }          // 静默跳过
  }
}
```

守卫是**累计计数**的（本机 safe-delete 单轮累计删到 50 个后拦截）。
一旦撞上，被拦下的那批文件永远排在 `walk()` 结果最前面，于是每轮 `slice` 切到的
都是同一批删不掉的 —— 跑满 100 轮，一个新文件都没删。
我原来的 `catch {}` 注释写「下一轮不再重试同一文件」，**但代码里没做到**（没有记录失败项）。

team-lead 改成用 `failed` Set 跨轮跳过失败项。**复验输出**：

```
第 1 次后: 97 个   注意:clean() 后仍剩 50 个文件(已清理 47 个,剩余删不动)
第 2 次后: 97 个   注意:clean() 后仍剩 97 个文件(上一轮的产物一个都没删掉)
index-*.js 副本数: 1   (dist/assets/index-CtmzX7qr.js)
```

**连跑两次都是 97，数量不增长，`index-*.js` 只有一个副本 —— 修法成立。**

顺带改了 `reportLeftover()` 的两处措辞：

1. 「可能被预览服务占用」是**错的**。实测单轮 unlinkSync 删 121 个文件：成功 49、失败 72，
   失败原因**全部**是 `[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":50 → 23 个}`，
   **零个 EBUSY/占用**。改成写明批量删除守卫。
2. 「97 个旧产物未删掉任何旧产物」这个句子读起来像清理失败，实际是「上一轮产物被守卫全拦下」，
   改成「clean() 后仍剩 N 个文件(原因)」。

---

## 二、SPEC §12.6 移动端视觉（375px）

**方法**：系统 Chrome headless + CDP（无 playwright/puppeteer 依赖），
视口 `375x812` dpr=3，**注入 alice 的真实 token**（后端 18080 代理），
静态服务 `scripts/serve-dist.mjs`（SPA fallback + API 代理）。

### 2.1 灵敏度自检（`node scripts/verify-mobile.mjs --self-test`）

```
灵敏度自检模式：注入已知违规，验证脚本能否抓到

  [PASS] 1. 30x44 的 button —— 必须被抓（热区判据）
         抓到: 30x44
  [PASS] 2. 1200px 宽的 div —— 必须报出横向滚动
         hScroll=true  scrollWidth=1216  vw=375  溢出元素: skip-link(right=-9902),
         selftest-wide(right=1216),tabbar(right=1216),tabbar__list(right=1216)...
  [PASS] 3. left:-9999px 的 a —— 必须 NOT 被抓（防 .skip-link 回归）
         正确未报(该元素在屏外)
  [PASS] 4. 纵向堆叠的两个 div —— stack.stacked 必须为 true（AC-16 判据）
         home-inner[tops=[93,159,219] distinct=3 stacked=true]
         feed-list[tops=[219,562,873,...] distinct=11 stacked=true]
         tabbar__list[tops=[742,742,742,742,742] distinct=1 stacked=false]
         selftest-page[tops=[300,340] distinct=2 stacked=true]
  [PASS] 5. 并排两个 div（同一 top）—— 必须报 stacked=false
         ...
         selftest-page[tops=[420,420] distinct=1 stacked=false]

自检 5/5 通过
判据有效：已知违规能被抓到，已知的假违规不会被报出
```

**这个自检抓出了三个真缺陷**（详见 §2.4），其中两个是判据本身的。

### 2.2 AC-15 热区 + 横向滚动：8 路由

| 路由 | 横向滚动 | <44px 可点元素 | TabBar |
|---|---|---|---|
| `/login/form` | 无 | 0 | 5 项全 OK |
| `/register` | 无 | 0 | 无 TabBar（设计如此） |
| `/` | 无 | 0 | 5 项全 OK |
| `/circles` | 无 | 0 | 5 项全 OK |
| `/notify` | 无 | 0 | 5 项全 OK |
| `/chat` | 无 | 0 | 5 项全 OK |
| `/publish` | 无（**修过**） | 0 | 无 TabBar（`hideTabBar`） |
| `/post/1` | 无 | 0 | 无 TabBar（`hideTabBar`） |

TabBar 五项实测：`首页 75x56 / 圈子 75x56 / 发布 75x61.6 / 消息 75x56 / 我的 75x56` —— 全部 ≥44。

**注**：`/login/form` 会输出「未停在该路由上（被守卫重定向）」——已登录用户访问登录页被送回 Home，
这是路由守卫的设计（`router/index.js` 的 `requiresAuth` 体系）。该行数据不是登录页的，已在输出里标注。

### 2.3 AC-16 Chat 单栏：**真判定通过**

用 `getBoundingClientRect().top` 分布判定（同一 top = 并列多列，不同 top = 纵向堆叠）：

```
--- Chat (/chat) ---
  子元素排布: .chat__list 下 2 个直接子元素,top 值 [85,141]
    不同 top 数 = 2  =>  纵向堆叠(单栏)
  子元素排布: .tabbar__list 下 5 个直接子元素,top 值 [742,742,742,742,742]
    不同 top 数 = 1  =>  同 top（并列多列）
```

`.chat__list` 的 2 个直接子元素 top 值不同（85 / 141）→ **纵向堆叠 = 单栏，AC-16 通过。**

**`.tabbar__list` 是判据正确性的天然交叉验证**：5 个 TabBar 项 top 全相同（742）→ 判为「并列多列」。
这证明判据不会把「并列」误报成「纵向」。

其余 7 个路由同样有单栏数据：`home-inner tops=[93,159,219] distinct=3`、
`feed-list tops=[219,562,...] distinct=11`、`pub__body tops=[146,179,689,722,780] distinct=5` —— 均纵向堆叠。

`/register` 与 `/post/1` 报「**未找到可判定的容器 —— 这一项是「未验证」，不是「单栏」**」。
这是如实标注：AC-16 只针对 Chat，这两页不需要单栏结论。**「没找到」不等于「单栏」。**

### 2.4 自检抓出的三个真缺陷

**(1) 横向滚动判据从未生效过（真缺陷，最严重）**

原写法：
```js
const vw = window.innerWidth;
hScroll = de.scrollWidth > vw + 1;
```

**移动端 viewport 会跟着内容宽度走** —— 注入 1200px 的 div 后实测
`scrollWidth=1216 而 vw=1216`，两者相等，于是判据「正确地」报无横向滚动。

也就是说：我上一轮报的「8 路由无横向滚动」，实际是「判据坏着所以报无」。改成
`de.clientWidth`（视口减去滚动条，不随内容变化）后立刻抓到 Publish 页的真实溢出。

**(2) 判据「命中第一个容器就 return」（设计缺陷）**

原设计里 `stack` 只返回第一个命中的容器，导致结论取决于**选择器书写顺序**而不是页面结构。
自检用例 5（并排两个 div，期望 `stacked=false`）直接暴露：判据先撞见页面的 `home-inner`
（纵向，stacked=true）就返回了，压根没看到注入的并列容器。改成**全量收集所有候选容器**。

**(3) `class$=` 属性选择器让整个脚本语法报错**

MEASURE 是模板字符串，`[class$="-list"]` 里的 `$` 被 Node 当成插值起点，
`MEASURE` 直接语法崩溃、整个脚本跑不起来（本文件当时是能跑的，加完选择器就崩了）。
已统一改用 `*=` 与显式类名，并在注释里写明原因。

---

## 三、本轮修掉的真实缺陷

### 3.1 Publish 页横向滚动（AC-15/§12.6）

判据修好后第一次抓到：

```
--- Publish (/publish) ---
  横向滚动: 有 (scrollW=391 > vw=375)
  溢出元素:
    div.el-textarea.pub__editor  left=16 right=391
```

诊断（`getComputedStyle` 实测）：

```
div.el-textarea.pub__editor  left=16 right=391 w=375
  minWidth=0px  flex=1 1 0%  boxSizing=content-box
  父 .pub__body 宽=343
```

两个叠加原因：

1. **flex item 默认 `min-width: auto`** —— 被内容撑破 → 加 `min-width: 0`
2. **项目没有全局 `box-sizing: border-box`**（核实过 `src/styles/*.css` 与 `index.html`，一处都没有）——
   content-box 下 padding 加在 width 之外。实测算式：
   `.pub__body` 内容区宽 359 → `.pub__editor` 的 width 被 flex 算成 359 →
   再加左右各 `var(--ys-gutter-phone)` = 16px → **359 + 16 + 16 = 391**，而视口只有 375。

修法：`min-width: 0` + `box-sizing: border-box` **只给这一个元素**。
不加全局 border-box 是因为那影响所有元素的盒模型计算，属于设计决策（SPEC 该不该要求全局
border-box），不该由一个横向滚动的修复顺带决定。

复测：`横向滚动: 无`。

### 3.2 四处热区（上一轮已改，本轮复核确认）

| 位置 | 修前实测 | 修法 |
|---|---|---|
| `Circles.css` `.circles__search-go` | 37x44 | `min-width: var(--ys-touch-min)` |
| `ChatConvList.css` `.quota__why` | 40x44 | 同上 |
| `Notify.vue` `.n-head` 「全部已读」 | 62x20 | `:deep(.el-button.is-link)` 补 min-w/h |
| `Home.vue` `.feed-tabs` / 搜索按钮 | 48x40 / 60x32 | `:deep(.el-tabs__item)` 与 append 按钮补 min-height |

全部用 `var(--ys-touch-min)`，无硬编码。

### 3.3 `PostComments.vue` 注释用词更正

原注释写「模板里写的是 `:overflow-of="overflowApi"`……而 `overflowApi` 既不是 prop 也不是本地定义」——
这描述的是**修复前**的状态，但读起来像在说当前状态，会让人去找一个已被替换的名字。
改成「修复前的状态（留档）」+ 明确说明「现在模板里搜不到 `overflowApi` 是正常的，它已被替换掉」。

---

## 四、门禁真实输出

```
$ cd frontend && python scripts/verify-frontend.py
自检通过：扫到 171 个源文件，其中 .vue 69 个
=== A1. @media(--ys-bp-*) ===  命中 0
=== A2. el-col 断点 props ===  命中 0
=== A3. 直接 from element-plus ===  命中 0
=== A4. emoji ===  命中 0
=== A5. 弹跳/过冲缓动 ===  命中 0
=== B1. 硬编码颜色 ===  命中 3（全为 #fff，契约允许例外）真正待处理：0 处
【C 组】768px 12 处 / 1024px 15 处；不在设计稿断点内的：无
【D 组】29 个 CSS 文件，共 1789 处 var(--ys-*)

$ node scripts/check-composable-exports.mjs
解构契约检查 23 处 / 30 个 composable 文件
  所有对象解构均已核对 return
解构契约检查通过

$ node scripts/check-sfc.mjs
检查 126 个文件(其中 SFC 69 个)
编译器零提醒
全部通过

$ node scripts/check-template-refs.mjs
模板标识符检查通过
```

---

## 五、新增脚本

| 脚本 | 职责 | 是否有自检 |
|---|---|---|
| `scripts/serve-dist.mjs` | SPA fallback + `/api/*` 代理到后端 18080 | 否（纯工具） |
| `scripts/verify-mobile.mjs` | 375px CDP 量测（AC-15/AC-16） | **是**，`--self-test` 5 用例 |

**`verify-mobile.mjs` 必须先跑 `--self-test` 再看量测结果** ——
「全通过」只证明没抓到违规，一个从不报错的脚本也给同样的输出。

---

## 六、未验证项（如实标注）

1. **`/register` 与 `/post/1` 的单栏结论** —— 判据未找到可判定容器，标为「未验证」。
   AC-16 只针对 Chat，这两页不需要该结论。
2. **`/login/form` 的量测数据** —— 已登录访问被守卫重定向，输出里已标注「数据不是该页的」。
   要真量登录页需清 token 重跑。
3. **聊天 WebSocket 实时消息的 375px 表现** —— 本轮只量了静态首屏，没测 WS 推送后的布局。
4. **键盘弹起（`--kb-h`）时的布局** —— SPEC §11 坑 8 提到 SafeArea，需真机键盘才验。
   本轮是 headless，无法覆盖。