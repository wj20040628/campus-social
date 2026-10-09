/**
 * SPEC §12.6 移动端视觉验证(AC-15/AC-16),375px 视口。
 * 用系统 Chrome 的 headless + CDP(无 playwright/puppeteer 依赖)。
 *
 * 用法:
 *   node scripts/verify-mobile.mjs               # 量 8 个路由
 *   node scripts/verify-mobile.mjs --self-test   # 灵敏度自检:注入已知违规,确认抓得到
 *
 * **先跑 --self-test 再看量测结果。** 「全通过」只证明没抓到违规,
 * 一个从不报错的脚本也给同样的输出。本项目已吃过三次这种假绿。
 *
 * 前置:需先起 dist 服务(node scripts/serve-dist.mjs dist 5178)。
 */
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
import http from 'node:http'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const BASE = process.env.SERVE_BASE || 'http://127.0.0.1:5173'
const PORT = 9222
const VIEWPORT = { width: 375, height: 812, deviceScaleFactor: 3, mobile: true }
/** --self-test:只跑灵敏度自检,量完即退出（不输出各路由报告） */
const SELF_TEST = process.argv.includes('--self-test')

/**
 * 路由表。
 *
 * `/login` 与 `/register` 不是真实路径 —— router/index.js:26-29 里
 * `/login` 只是个 redirect,真路由是 `/login/form`。第一轮我把它们写成
 * `/login` `/register`,量出来的是重定向后的页面,已修正。
 * 但**注入 token 后再访问这两个页会被守卫踢回 Home**(见 ensureOn 的告警),
 * 所以这两个路由放在注入前测。
 *
 * admin 路由**必须单列**,不能混进上面这张表:它们 requiresAdmin,
 * 而注入的是普通用户 alice(role≠1)—— 守卫会把她踢回 Home,
 * 于是量到的是首页,并给出一堆「无横向滚动、无小热区」的**假通过**。
 * 「量的是别的页面」比「没量」更危险,因为它看起来是绿的。
 * 管理员路由走 injectAdmin() 换成 role=1 的身份单独跑。
 */
const ROUTES = [
  { path: '/login/form', name: 'Login' },
  { path: '/register', name: 'Register' },
  { path: '/', name: 'Home' },
  { path: '/circles', name: 'Circles' },
  { path: '/notify', name: 'Notify' },
  { path: '/chat', name: 'Chat' },
  { path: '/publish', name: 'Publish' },
  { path: '/post/1', name: 'PostDetail' },
]

/** 管理员路由(OD-19:后台页在 375px 视口必须可用) */
const ADMIN_ROUTES = [
  { path: '/admin/verify', name: 'AdminVerify' },
  { path: '/admin/review', name: 'AdminReview' },
]

function get(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let d = ''
      res.on('data', (c) => (d += c))
      res.on('end', () => resolve(JSON.parse(d)))
    }).on('error', reject)
  })
}

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  '--no-sandbox',
  '--disable-gpu',
  '--hide-scrollbars',
  'about:blank',
], { stdio: 'ignore' })

let ws
try {
  // 等 CDP 端口就绪
  let list = null
  for (let i = 0; i < 40; i += 1) {
    try { list = await get(`http://127.0.0.1:${PORT}/json/list`); break } catch { await sleep(250) }
  }
  if (!list) throw new Error('CDP 未就绪')

  const { default: WS } = await import('node:worker_threads').then(() => ({ default: null })).catch(() => ({ default: null }))
  // 用内置 WebSocket(Node 22+)
  const target = list.find((t) => t.type === 'page')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })

  let id = 0
  const pending = new Map()
  const events = []
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data)
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id) }
    else if (msg.method) events.push(msg)
  }
  const send = (method, params = {}) => new Promise((res) => {
    id += 1
    pending.set(id, res)
    ws.send(JSON.stringify({ id, method, params }))
  })

  await send('Page.enable')
  await send('Runtime.enable')
  /**
   * 固定「无动画偏好」。
   *
   * headless Chrome 的默认状态各版本不一致(有的默认 reduce、有的 no-preference),
   * 而 reduced-motion 下 `transition-duration` 会被压到 0.01ms ——
   * 那样量不到真实的过渡时长,也就量不到「过渡期间内容区是否位移」这类问题。
   * 写死 no-preference,不依赖默认值。
   */
  await send('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }],
  })
  await send('Emulation.setDeviceMetricsOverride', VIEWPORT)

  /**
   * 注入 token 后再量。
   *
   * 不注入的话,除 login/register 外**所有页都会被路由守卫重定向到 /login**
   * (router/index.js:39-90 全部 requiresAuth)—— 量出来的是登录表单,
   * 不是被测页面。首轮实测就是这样:六个路由的输出完全一样。
   */
  const tokenRes = await fetch('http://127.0.0.1:18080/auth/login', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username: 'alice@campus.test', password: 'user123' }),
  }).then((r) => r.json())
  const token = tokenRes?.data?.accessToken
  if (!token) throw new Error('拿不到 token:' + JSON.stringify(tokenRes).slice(0, 200))
  await send('Page.navigate', { url: BASE + '/' })
  await sleep(1200)
  await send('Runtime.evaluate', {
    expression: `localStorage.setItem('access_token', ${JSON.stringify(token)});`
      + `localStorage.setItem('refresh_token', ${JSON.stringify(tokenRes.data.refreshToken || '')});`,
  })
  console.log(`已注入 alice 的 token（uid 2）`)

  /**
   * 换成管理员身份,量 ADMIN_ROUTES。
   *
   * isAdmin 的判据是 `userStore.userInfo?.role === 1`,而 userInfo **只从
   * localStorage 的 user_info 读**(stores/user.js:12),不随 token 一起注入。
   * 所以必须同时写两个键:access_token 换成管理员的,
   * user_info 补一条 role=1 —— 只换 token 的话守卫照样踢回 Home。
   *
   * 管理员账号取 admin@campus.test:实测后端 seed 里 role=1 的就这一个,
   * 换别的名字会拿到 role=null,那样量到的又是首页。
   */
  async function injectAdmin() {
    const res = await fetch('http://127.0.0.1:18080/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: 'admin@campus.test', password: 'admin123' }),
    }).then((r) => r.json())
    const adminToken = res?.data?.accessToken
    if (!adminToken) throw new Error('拿不到管理员 token:' + JSON.stringify(res).slice(0, 200))
    // 先访问一次让 SPA 建 store,再写 localStorage 并整页刷新
    await send('Page.navigate', { url: BASE + '/' })
    await sleep(1000)
    await send('Runtime.evaluate', {
      expression:
        `localStorage.setItem('access_token', ${JSON.stringify(adminToken)});`
        + `localStorage.setItem('refresh_token', ${JSON.stringify(res.data.refreshToken || '')});`
        // role=1 是 isAdmin 的唯一判据;其余字段只为了让 store 的 nickname getter 不炸
        + `localStorage.setItem('user_info', JSON.stringify({ id: 1, username: 'admin@campus.test', nickname: '管理员', role: 1 }));`,
    })
    console.log('已注入管理员身份（role=1）')
  }

  /** 量测前先确认真的停在该路由上，否则量的是重定向后的页面 */
  async function ensureOn(route) {
    for (let i = 0; i < 3; i += 1) {
      const r = await send('Runtime.evaluate', { expression: 'location.pathname', returnByValue: true })
      const got = r.result?.result?.value
      if (got === route) return true
      await sleep(400)
    }
    return false
  }

  const MEASURE = `(() => {
    const de = document.documentElement;
    /**
     * 横向滚动判据 —— 这里踩过一个坑,判据本身要改。
     *
     * 原写法:const vw = window.innerWidth; hScroll = de.scrollWidth > vw + 1
     * 实测**失效**:移动端 viewport 会跟着内容宽度走,
     * 注入一个 1200px 的 div 后量到 scrollWidth=1216 而 vw=1216,两者相等,
     * 于是判据「正确地」报无横向滚动 —— --self-test 的用例 2 直接 FAIL。
     *
     * 原因:有横向溢出时浏览器会把 layout viewport 撑到内容宽度,
     * innerWidth 随之变大,所以 scrollWidth > innerWidth 永远不成立。
     *
     * 现在用 de.clientWidth:它是**视口减去滚动条**的固定宽度,
     * 不随内容变化。实测注入后 clientWidth 仍是 375,scrollWidth 1216,
     * 溢出被正确检出。
     */
    const vw = de.clientWidth;
    const hScroll = de.scrollWidth > vw + 1;
    // 找出造成横向溢出的元素(取最外层几个)
    const offenders = [];
    if (hScroll) {
      for (const el of document.querySelectorAll('*')) {
        const r = el.getBoundingClientRect();
        if (r.right > vw + 1 || r.left < -1) {
          offenders.push({
            tag: el.tagName.toLowerCase(),
            cls: (el.className && el.className.baseVal !== undefined ? el.className.baseVal : String(el.className || '')).slice(0, 50),
            left: Math.round(r.left), right: Math.round(r.right),
          });
        }
        if (offenders.length >= 6) break;
      }
    }
    // 可点元素热区
    const small = [];
    const sel = 'button, a, [role="button"], [role="tab"], input[type="submit"], .el-button, [class*="tabbar"] *';
    for (const el of document.querySelectorAll(sel)) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;           // 不可见
      // 排除移出屏外的无障碍跳转链接(.skip-link 用 left:-9999px,聚焦时才归位)
      // —— 它在屏外不算热区,算进去会得到一个假的违规项。
      // 判据用视口相交而不是 left 值:有的实现用 transform,不用 left。
      // 用 de.clientWidth 与上面 hScroll 保持同一口径。
      const inViewport = r.right > 0 && r.left < de.clientWidth
        && r.bottom > 0 && r.top < de.clientHeight;
      if (!inViewport) continue;
      const st = getComputedStyle(el);
      if (st.display === 'none' || st.visibility === 'hidden') continue;
      const clickable = el.tagName === 'BUTTON' || el.tagName === 'A'
        || el.getAttribute('role') === 'button' || el.getAttribute('role') === 'tab';
      if (!clickable) continue;
      if (r.width < 44 || r.height < 44) {
        small.push({
          tag: el.tagName.toLowerCase(),
          cls: String(el.className || '').slice(0, 46),
          w: Math.round(r.width * 10) / 10, h: Math.round(r.height * 10) / 10,
          text: (el.textContent || '').trim().slice(0, 14),
        });
      }
    }
    return {
      vw, scrollW: de.scrollWidth, hScroll, offenders,
      smallCount: small.length, small: small.slice(0, 12),
      /**
       * AC-16 单栏判定。
       *
       * [为什么不看 display]
       * 第一版这里写「flex → 无法从计算值判定列数」,等于放弃了判定。
       * 而「没有报错」不等于「是单栏」—— display:flex 里放三个 flex:1 的子元素,
       * 浏览器同样不会报错。放弃判定会被读成「通过」。
       *
       * [现在怎么判]
       * 两条独立判据,都要报出来:
       *   1. gridTemplateColumns:grid 容器才有值。'none' = 单列。
       *   2. **子元素的 top 值**:同一行的元素 top 相同,并列多列;
       *      纵向堆叠的元素 top 互不相同。这条与 display 无关,
       *      flex 容器也适用 —— flex-direction:row 时 top 相同,
       *      column 时 top 递增。所以它是通用判据。
       */
      cols: (() => {
        for (const el of document.querySelectorAll('main, .content, [class*="layout"], [class*="page"], [class*="chat"], [class*="_inner"], .home-inner, .feed-list, .home')) {
          const st = getComputedStyle(el);
          if (st.display.includes('grid')) {
            const tpl = st.gridTemplateColumns;
            return { display: st.display, gridTemplateColumns: (tpl || 'none').slice(0, 120) };
          }
        }
        return { display: '(非 grid,用 top 判据)', gridTemplateColumns: 'n/a' };
      })(),
      /** 直接子元素的 top 分布:top 互不相同 => 纵向堆叠(单栏) */
      stack: (() => {
        // 选择器要覆盖真实的内容容器。第一版只有 main/[class*=layout] 等 5 个,
        // 而实际主容器是 MainLayout 的 .content、页面根是 .home
        // —— 于是多数页面报「未找到可判定的容器」,
        // 而**「没找到」不等于「单栏」**,只是判据没跑起来。
        //
        // 注意 class*= 里的 $: 本函数体是**模板字符串**,[class*="_inner"], .home-inner, .feed-list 里的
        // 会被 Node 当成插值起点,整个 MEASURE 直接语法报错(改选择器时踩过一次)。
        // 规避:属性选择器一律用 *=, 或者把 $ 转义。本文件统一用前者。
        // 注意 class*= 里的 $: 本函数体是**模板字符串**,[class$="-list"] 里的
        // $ 会被 Node 当成插值起点,整个 MEASURE 直接语法报错(改选择器时踩过一次)。
        // 所以这里一律用 *= 与显式类名,不用 $= 与 ^=。
        //
        // **返回全部候选而不是第一个** —— 原设计「命中第一个就 return」有隐藏问题:
        // 判定结果取决于选择器的书写顺序,而不是页面真实结构。
        // 自检用例 5(并排两个 div,期望 stacked=false)就是这么暴露的:
        // 判据先命中了页面的 home-inner(纵向排布,stacked=true)就返回,
        // 压根没看到注入的并列容器,于是「并列」被误判成「纵向」。
        // 判据不能因为「先撞见谁」而改变结论,所以全量收集。
        const stacks = [];
        for (const el of document.querySelectorAll('main, .content, .home, .auth-page, .detail, [class*="layout"], [class*="page"], [class*="chat"], [class*="conv"], [class*="_inner"], .home-inner, .feed-list, [class*="__list"]')) {
          const kids = [...el.children].filter((k) => {
            const r = k.getBoundingClientRect();
            const st = getComputedStyle(k);
            return r.width > 0 && r.height > 0 && st.display !== 'none' && st.position !== 'fixed' && st.position !== 'absolute';
          });
          if (kids.length < 2) continue;
          const tops = kids.map((k) => Math.round(k.getBoundingClientRect().top));
          const uniq = [...new Set(tops)];
          stacks.push({
            container: (el.className || el.tagName).toString().slice(0, 50),
            childCount: kids.length,
            tops: tops.slice(0, 10),
            distinctTops: uniq.length,
            /** 关键:不同 top 的数量 > 1 => 这些元素纵向排布 => 单栏 */
            stacked: uniq.length > 1,
          });
        }
        return stacks;
      })(),
      tabbar: (() => {
        // 选择器要精确:第一版用 [class*="tabbar"] 会命中页面容器
        // (量出 844px = 整个页面高),失去意义。
        // 真实的移动端 TabBar 在 MainLayout 里,类名见 MobileTabBar。
        const tb = document.querySelector('nav.tabbar, .tabbar');
        if (!tb) return null;
        const r = tb.getBoundingClientRect();
        const st = getComputedStyle(tb);
        // 再挑它内部真正的可点项,量 TabBar 自身的热区(AC-15 的重点)
        const items = [...tb.querySelectorAll('a, button, [role="tab"]')].map((el) => {
          const ir = el.getBoundingClientRect();
          return {
            w: Math.round(ir.width * 10) / 10, h: Math.round(ir.height * 10) / 10,
            ok: ir.width >= 44 && ir.height >= 44,
            text: (el.textContent || '').trim().slice(0, 10),
          };
        });
        return { h: Math.round(r.height), display: st.display, visible: r.height > 0, items };
      })(),
    };
  })()`

  /**
   * ============================================================
   * 灵敏度自检(--self-test)
   * ============================================================
   *
   * ## 为什么必须有这个模式
   *
   * 「8 路由全通过」是极强的结论,而它只证明「没抓到违规」——
   * 一个从不报错的脚本也能给出同样的输出。**「全部通过」和「检查项根本没在跑」
   * 在终端上长得一模一样。** 这个项目里已经吃过三次:
   * verify-frontend.py 扫空、t_mask.py 正则被 *** 打断、契约检查被 shell 转义吃掉。
   *
   * 所以:注入**它应该报出的**违规,确认它真的报得出来。
   * 三个用例覆盖三类判据,外加一个「不该报的」防回归:
   *   1. 30x44 的 button      -> 必须出现在 small 列表里(热区判据有效)
   *   2. 1200px 宽的 div      -> 必须报出 hScroll(横向滚动判据有效)
   *   3. left:-9999px 的 a    -> **必须不出现**(防 .skip-link 假违规回归)
   *   4. 纵向堆叠的两个 div    -> stack.stacked 必须为 true(AC-16 判据有效)
   */
  if (SELF_TEST) {
    console.log('灵敏度自检模式：注入已知违规，验证脚本能否抓到')
    console.log('')
    await send('Page.navigate', { url: BASE + '/' })
    await sleep(1200)

    const cases = [
      {
        name: '1. 30x44 的 button —— 必须被抓（热区判据）',
        expect: 'small',
        inject: `(() => {
          const b = document.createElement('button');
          b.className = 'selftest-tiny-btn';
          b.textContent = 'X';
          b.style.cssText = 'position:fixed;left:10px;top:200px;width:30px;height:44px;z-index:99999';
          document.body.appendChild(b);
        })()`,
        check: (m) => m.small.some((s) => s.cls.includes('selftest-tiny-btn')),
        detail: (m) => {
          const hit = m.small.find((s) => s.cls.includes('selftest-tiny-btn'));
          return hit ? `抓到: ${hit.w}x${hit.h}` : `未抓到, small 里只有: ${JSON.stringify(m.small.map((s) => s.cls))}`;
        },
      },
      {
        name: '2. 1200px 宽的 div —— 必须报出横向滚动',
        expect: 'hScroll=true',
        // 注意不能只加一个 1200px 的 div:那会让文档 scrollWidth 变成 1200,
        // 而 vw 也跟着变成 1200(移动端 viewport 会跟着内容宽度走) ——
        // 于是 scrollW > vw 不成立,判据「正确地」报无横向滚动。
        // 第一版注入就是这么写的,自检直接 FAIL。
        // 正确做法:让溢出发生在**一个已存在的窄容器内部**(模拟真实场景:
        // 某个卡片里的宽表格/长代码块把容器撑破),vw 仍是 375。
        inject: `(() => {
          const host = document.querySelector('main') || document.body;
          const d = document.createElement('div');
          d.className = 'selftest-wide';
          d.style.cssText = 'width:1200px;height:10px;background:currentColor';
          host.appendChild(d);
        })()`,
        check: (m) => m.hScroll === true,
        detail: (m) => `hScroll=${m.hScroll}  scrollWidth=${m.scrollW}  vw=${m.vw}`
          + (m.offenders.length ? `  溢出元素: ${m.offenders.map((o) => `${o.cls}(right=${o.right})`).join(',')}` : '  (无溢出元素记录)'),
      },
      {
        name: '3. left:-9999px 的 a —— 必须 NOT 被抓（防 .skip-link 回归）',
        expect: 'small 不含它',
        inject: `(() => {
          const a = document.createElement('a');
          a.className = 'selftest-offscreen';
          a.href = '#';
          a.textContent = '跳到主内容';
          a.style.cssText = 'position:absolute;left:-9999px;top:10px;width:97px;height:38px';
          document.body.appendChild(a);
        })()`,
        check: (m) => !m.small.some((s) => s.cls.includes('selftest-offscreen')),
        detail: (m) => {
          const hit = m.small.find((s) => s.cls.includes('selftest-offscreen'));
          return hit ? `误报了: ${hit.w}x${hit.h} —— 视口相交判据失效了` : '正确未报(该元素在屏外)';
        },
      },
      {
        name: '4. 纵向堆叠的两个 div —— stack.stacked 必须为 true（AC-16 判据）',
        expect: 'stacked=true',
        // 容器挂在 body 下,类名要含 page/layout/chat/conv 之一,
        // 否则 stack 的选择器扫不到它 —— 第一版注入用 'selftest-stack',
        // 判据的 querySelectorAll 里没有这个词,于是 stack=null、自检 FAIL。
        // 那次 FAIL 说明的是「注入用例没命中判据的作用域」,不是判据本身坏了。
        inject: `(() => {
          const c = document.createElement('div');
          c.className = 'selftest-page';
          c.style.cssText = 'position:fixed;left:5px;top:300px;width:200px;z-index:99999';
          c.innerHTML = '<div style="width:190px;height:40px;background:currentColor"></div>'
            + '<div style="width:190px;height:40px;background:currentColor"></div>';
          document.body.appendChild(c);
        })()`,
        check: (m) => Array.isArray(m.stack) && m.stack.some((s) => s.container.includes('selftest-page') && s.stacked === true),
        detail: (m) => (Array.isArray(m.stack) && m.stack.length
          ? m.stack.map((s) => `${s.container}[tops=${JSON.stringify(s.tops)} distinct=${s.distinctTops} stacked=${s.stacked}]`).join('\n           ')
          : 'stack 为空 —— 没找到可判定容器，判据没跑起来'),
      },
      {
        name: '5. 并排两个 div（同一 top）—— 必须报 stacked=false',
        expect: 'stacked=false',
        // 反向用例:只有能区分「纵向」和「并列」的判据才算数。
        // 只会报 true 的判据等于没判。
        // 这个用例还暴露了另一个真缺陷:判据原设计是「命中第一个容器就 return」,
        // 于是结论取决于选择器书写顺序 —— 它先撞见页面的 home-inner(纵向)
        // 就返回了,压根没看到注入的并列容器。改成全量收集才对。
        inject: `(() => {
          const c = document.createElement('div');
          c.className = 'selftest-page';
          c.style.cssText = 'position:fixed;left:5px;top:420px;width:400px;display:flex;z-index:99999';
          c.innerHTML = '<div style="width:190px;height:40px;background:currentColor"></div>'
            + '<div style="width:190px;height:40px;background:currentColor"></div>';
          document.body.appendChild(c);
        })()`,
        check: (m) => Array.isArray(m.stack) && m.stack.some((s) => s.container.includes('selftest-page') && s.stacked === false),
        detail: (m) => (Array.isArray(m.stack) && m.stack.length
          ? m.stack.map((s) => `${s.container}[tops=${JSON.stringify(s.tops)} distinct=${s.distinctTops} stacked=${s.stacked}]`).join('\n           ')
          : 'stack 为空 —— 没找到可判定容器'),
      },
    ]

    let pass = 0
    for (const c of cases) {
      // 每个用例都先重建页面，避免上一个用例的注入残留影响下一个
      await send('Page.navigate', { url: BASE + '/' })
      await sleep(900)
      await send('Runtime.evaluate', { expression: c.inject })
      await sleep(250)
      const res = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
      const m = res.result?.result?.value
      if (!m) {
        console.log(`  [FAIL] ${c.name}`)
        console.log('         MEASURE 没返回结果，判据可能整段没执行')
        continue
      }
      const ok = c.check(m)
      if (ok) pass += 1
      console.log(`  [${ok ? 'PASS' : 'FAIL'}] ${c.name}`)
      console.log(`         ${c.detail(m)}`)
    }
    console.log('')
    console.log(`自检 ${pass}/${cases.length} 通过`)
    if (pass !== cases.length) process.exitCode = 1
    else console.log('判据有效：已知违规能被抓到，已知的假违规不会被报出')
  } else {

  const results = []
  for (const r of ROUTES) {
    await send('Page.navigate', { url: BASE + r.path })
    await sleep(1800)
    const onRoute = await ensureOn(r.path)
    const res = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
    const val = res.result?.result?.value
    results.push({ route: r.path, name: r.name, onRoute, ...(val || { error: JSON.stringify(res.result).slice(0, 200) }) })
  }

  // 管理员路由:先换身份,再量。不换身份量到的会是首页(见 ADMIN_ROUTES 注释)
  await injectAdmin()

  /**
   * 学号核验页的额外取证。
   *
   * 为什么单独一段而不是并进 MEASURE:MEASURE 是**通用**判据(横向滚动、
   * 热区、单栏),而这一页要回答的是「14 位学号到底有没有被截断」「三种解析
   * 分支分别渲染了什么」—— 这些是**该页专属**的事实,不是通用判据。
   * 混进 MEASURE 会让另外 9 个路由背上一个用不上的分支。
   *
   * 判据:
   *   截断 = scrollWidth > clientWidth + 1。offsetWidth 不算 ——
   *   overflow:hidden 的元素 offsetWidth 恒等于可见宽度,量不出被藏掉的部分。
   */
  const DUMP_VERIFY = `(() => {
    const cards = [...document.querySelectorAll('.vcard')].map((c) => {
      const no = c.querySelector('.vcard__no');
      const r = no.getBoundingClientRect();
      const st = getComputedStyle(no);
      return {
        text: (c.textContent || '').replace(/\\s+/g, ' ').trim(),
        studentNo: no.textContent.trim(),
        noTruncated: no.scrollWidth > no.clientWidth + 1,
        noScrollW: no.scrollWidth, noClientW: no.clientWidth,
        noFontSize: st.fontSize, noFontFamily: st.fontFamily.split(',')[0],
        noTabular: st.fontVariantNumeric, noWhiteSpace: st.whiteSpace,
        noRectW: Math.round(r.width * 10) / 10,
        hasTag: !!c.querySelector('.vcard__tag'),
        tagText: (c.querySelector('.vcard__tag')?.textContent || '').trim(),
        parsed: (c.querySelector('.vcard__parsed')?.textContent || '').trim(),
        claimed: (c.querySelector('.vcard__claimed')?.textContent || '').trim(),
        btns: [...c.querySelectorAll('.vcard__btn')].map((b) => {
          const br = b.getBoundingClientRect();
          return {
            t: b.textContent.trim(),
            w: Math.round(br.width * 10) / 10, h: Math.round(br.height * 10) / 10,
            disabled: b.disabled,
          };
        }),
      };
    });
    return { count: cards.length, cards };
  })()`

  for (const r of ADMIN_ROUTES) {
    await send('Page.navigate', { url: BASE + r.path })
    await sleep(1800)
    const onRoute = await ensureOn(r.path)
    const res = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
    const val = res.result?.result?.value
    const row = { route: r.path, name: r.name, onRoute, ...(val || { error: JSON.stringify(res.result).slice(0, 200) }) }
    if (r.path === '/admin/verify' && onRoute) {
      const d = await send('Runtime.evaluate', { expression: DUMP_VERIFY, returnByValue: true })
      row.verify = d.result?.result?.value || null
    }
    results.push(row)
  }

  console.log(`视口 ${VIEWPORT.width}x${VIEWPORT.height}  deviceScaleFactor=${VIEWPORT.deviceScaleFactor}`)
  console.log('')
  for (const r of results) {
    console.log(`--- ${r.name} (${r.route}) ---`)
    if (r.error) { console.log('  取值失败:', r.error); continue }
    if (!r.onRoute) console.log('  ** 未停在该路由上(被路由守卫重定向),以下数据不是该页的 **')
    console.log(`  横向滚动: ${r.hScroll ? '有 (scrollW=' + r.scrollW + ' > vw=' + r.vw + ')' : '无'}`)
    if (r.offenders?.length) {
      console.log('  溢出元素:')
      for (const o of r.offenders) console.log(`    ${o.tag}.${o.cls}  left=${o.left} right=${o.right}`)
    }
    console.log(`  布局: display=${r.cols.display}  gridTemplateColumns=${r.cols.gridTemplateColumns}`)
    if (Array.isArray(r.stack) && r.stack.length) {
      for (const s of r.stack) {
        console.log(`  子元素排布: ${s.container} 下${s.childCount} 个直接子元素,`
          + `top 值 ${JSON.stringify(s.tops)}`)
        console.log(`    不同 top 数 = ${s.distinctTops}  =>  ${s.stacked ? '纵向堆叠(单栏)' : '同top(并列多列)'}`)
      }
    } else {
      console.log('  子元素排布: ** 未找到可判定的容器 —— 这一项是「未验证」,不是「单栏」 **')
    }
    if (r.tabbar) {
      console.log(`  TabBar: 高 ${r.tabbar.h}px  display=${r.tabbar.display}  可见=${r.tabbar.visible}`)
      for (const it of r.tabbar.items || []) {
        console.log(`    ${it.ok ? 'OK  ' : '不足 '} ${it.w}x${it.h}  "${it.text}"`)
      }
    }
    console.log(`  <44px 的可点元素: ${r.smallCount} 个`)
    for (const s of r.small || []) console.log(`    ${s.tag}.${s.cls}  ${s.w}x${s.h}  "${s.text}"`)
    if (r.verify) {
      console.log(`  学号核验页实渲染 ${r.verify.count} 张卡片:`)
      for (const c of r.verify.cards) {
        console.log(`    · ${c.studentNo}  [${c.noFontSize} ${c.noFontFamily} ${c.noTabular} ${c.noWhiteSpace}]`
          + `  截断=${c.noTruncated ? '★是' : '否'}(scrollW=${c.noScrollW}/clientW=${c.noClientW}/rectW=${c.noRectW})`)
        console.log(`      解析行="${c.parsed}"  自填行="${c.claimed}"  标签="${c.tagText || '(无)'}"`)
        console.log(`      按钮: ${c.btns.map((b) => `${b.t} ${b.w}x${b.h}${b.disabled ? '(禁用)' : ''}`).join('  ')}`)
        console.log(`      全文: ${c.text}`)
      }
    }
    console.log('')
  }
  }
} finally {
  try { ws?.close() } catch {}
  chrome.kill()
}