/**
 * 路由切换白屏回归门禁（SPEC §12 新增）。
 *
 * 存在的理由：用户报「登录后切换首页/圈子/发动态/消息都白屏，刷新就好」。
 * 根因是 MainLayout 的<Transition mode="out-in"> 在 leave 阶段抛错时，
 * 永远走不到挂载新组件那一步 —— router-view 区域永久空白，只有 F5 能重置。
 *
 * [注] 为什么这个脚本必须自己带灵敏度验证：
 *   「页面不白屏」是一个极易造假的结论。判据一旦只写
 *   「量一下main 的 clientHeight > 0」，那么
 *     - main 为空但有 min-height 时会报「有内容」
 *     - 过渡动画进行中量到的是旧组件，会报「正常」
 *     - 守卫把路由踢回首页时报「正常」
 *   三种假绿都会让真bug 溜过去。所以下面每个判据都写清了它抓什么。
 *
 * 用法：
 *   node scripts/verify-route-switch.mjs            # 正常跑
 *   node scripts/verify-route-switch.mjs --self-test # 先证明判据能抓到白屏
 */
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'
import http from 'node:http'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const BASE = process.env.SERVE_BASE || 'http://127.0.0.1:5173'
const PORT = 9344
const VIEWPORT = { width: 375, height: 812, deviceScaleFactor: 3, mobile: true }

/** 路由切换序列：用户报的就是这几条。
 *
 *  [注] /publish 放最后：它是全屏表单页(MainLayout.vue 里 hideTabBar 为true)，
 *    没有 TabBar，点完它就没法再用 TabBar 导航去下一个页面。
 *  [注] /notify 不在 TabBar 里(通知入口是顶栏铃铛，见 TopBar.vue)，
 *    所以它靠 history.back() 回列表页继续。
 */
const ROUTES = [
  { path: '/', name: 'Home' },
  { path: '/circles', name: 'Circles' },
  { path: '/chat', name: 'Chat' },
  { path: '/', name: 'Home(回)' },
  { path: '/notify', name: 'Notify' },
  { path: '/', name: 'Home(回2)' },
  { path: '/publish', name: 'Publish' },
]

const getJson = (u) => new Promise((res, rej) => {
  http.get(u, (r) => { let d = ''; r.on('data', (c) => (d += c)); r.on('end', () => { try { res(JSON.parse(d)) } catch (e) { rej(e) } }) }).on('error', rej)
})

async function launch() {
  const p = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`,
    `--window-size=${VIEWPORT.width},${VIEWPORT.height}`,
    '--user-data-dir=C:\\Users\\Administrator\\AppData\\Local\\Temp\\cdp-routecheck',
    'about:blank',
  ], { stdio: 'ignore' })
  await sleep(2500)
  const list = await getJson(`http://127.0.0.1:${PORT}/json/list`)
  const t = list.find((x) => x.type === 'page')
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  let id = 0
  const waits = new Map()
  const consoleErrors = []
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data)
    if (waits.has(m.id)) { waits.get(m.id)(m.result); waits.delete(m.id) }
    if (m.method === 'Runtime.exceptionThrown') {
      const d = m.params?.exceptionDetails
      consoleErrors.push(d?.exception?.description || d?.text || 'unknown')
    }
    if (m.method === 'Runtime.consoleAPICalled' && (m.params?.type === 'error' || m.params?.type === 'warning')) {
      const txt = (m.params?.args || []).map((a) => a.value ?? a.description ?? '').join(' ')
      if (/parentNode|Unhandled error|invoked outside of the render function/i.test(txt)) {
        consoleErrors.push(txt.slice(0, 160))
      }
    }
  }
  await new Promise((r) => (ws.onopen = r))
  const send = (method, params = {}) => new Promise((r) => { const i = ++id; waits.set(i, r); ws.send(JSON.stringify({ id: i, method, params })) })
  await send('Page.enable'); await send('Runtime.enable')
  /**
   * 显式声明「无动画偏好」。
   *
   * 不设的话,headless Chrome 的默认状态各版本不一致(有的默认 reduce,
   * 有的默认 no-preference),而**这个脚本要验的正是过渡动画**:
   * 若在 reduce 下跑,`transition-duration: 0.01ms` 会让 leave 阶段瞬间完成,
   * out-in 卡死这个缺陷就**测不出来了** —— 判据会报「无白屏」,
   * 而真机上用户是有动画的。
   *
   * 所以量路由切换必须固定在 no-preference,且这个前提要写死,
   * 不能依赖 headless 的默认值。
   */
  await send('Emulation.setEmulatedMedia', {
    features: [{ name: 'prefers-reduced-motion', value: 'no-preference' }],
  })
  return { p, ws, send, consoleErrors }
}

/**
 * 量「router-view 区域是否有实质内容」，而不是只看高度。
 *
 * [注] 这里踩过一次坑：自检跑出 kids=undefined text=undefined，
 *   原因是未登录时首页根本不渲染 <main>，MEASURE 返回 found:false，
 *   于是四个用例全部落在 found:false 分支——
 *   **判据自己失效了，而输出看起来只是「多了一个 undefined」**。
 *   这类假绿比报错更危险：PASS 会被当成通过。
 *   修法：self-test 直接自己造一个容器，不依赖登录态与路由匹配。
 */
const MEASURE = `(() => {
  const main = document.querySelector('main') || document.querySelector('.content');
  if (!main) return { found: false, kids: 0, text: 0, media: 0, hasContent: false, why: 'no-container' };
  const r = main.getBoundingClientRect();
  const kids = main.querySelectorAll('*').length;
  const text = (main.innerText || '').trim().length;
  const media = main.querySelectorAll('img,svg,canvas,video').length;
  return {
    found: true,
    url: location.pathname,
    w: Math.round(r.width), h: Math.round(r.height),
    kids, text, media,
    hasContent: kids > 0 && (text > 0 || media > 0),
    why: kids === 0 ? 'empty' : (text === 0 && media === 0 ? 'no-visible-content' : ''),
  };
})()`

async function loginAndInject(send) {
  await send('Page.navigate', { url: `${BASE}/` })
  await sleep(2500)
  const r = await send('Runtime.evaluate', {
    expression: `(async () => {
      const res = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'alice@campus.test', password: 'user123' }),
      });
      const j = await res.json();
      if (!j || !j.data) return 'LOGIN_FAIL:' + JSON.stringify(j).slice(0, 120);
      localStorage.setItem('access_token', j.data.accessToken);
      localStorage.setItem('refresh_token', j.data.refreshToken || '');
      localStorage.setItem('userInfo', JSON.stringify(j.data.user || null));
      return 'OK';
    })()`,
    awaitPromise: true,
  })
  return r?.result?.value
}

async function gotoRoute(send, path) {
  /* [注] 第一版用 history.pushState + popstate 手动改 URL，结果 6 个路由全部报
   *   「找不到 main/.content 容器」——因为 vue-router 并不知道 URL 变了，
   *   它不会重新解析路由，页面根本没离开登录页。
   *   换句话说第一版测的是「一个从没导航过的页面」，量测毫无意义。
   *
   *   正确做法：点真实可点的元素（模拟用户），或调用 router 实例。
   *   这里优先点 TabBar/侧栏的链接 —— 因为「用户点导航栏切换」正是被测场景，
   *   点元素还能顺带覆盖 a 标签的 href 与守卫逻辑，比调router.push 更接近真实。
   */
  const nav = await send('Runtime.evaluate', {
    expression: `(() => {
      const target = ${JSON.stringify(path)};
      const norm = (h) => (h || '').split('?')[0].replace(/\\/$/, '') || '/';
      // ① 找真实导航链接。注意要取a 元素本身 —— TabBar 结构是
      //    li.tabbar__item > a.tabbar__link，只有点 a 才会触发路由跳转。
      //    （早先只匹配 li 的 class，点了个不导航的容器，静默无效。）
      const links = [...document.querySelectorAll('a[href]')]
        .filter(el => norm(el.getAttribute('href')) === norm(target));
      if (links.length) {
        links[0].click();
        return 'clicked:a[href=' + norm(target) + ']';
      }
      // ② /notify 不在 TabBar 里（通知入口是顶栏铃铛，见 TopBar.vue），
      //    但它仍是用户真实路径，所以点铃铛进去，而不是放弃检查。
      //    [注] 必须用「导航后 URL 是否符合期望」来判定成败，
      //      不能只看「点了一下」—— 点了但没跳过去也算失败。
      const bell = document.querySelector('.topbar a[href="/notify"], .topbar__bell, [aria-label*="通知"], [aria-label*="消息"]');
      if (bell && norm(target) === '/notify') {
        bell.click();
        return 'clicked:bell';
      }
      // ③ /publish 是全屏表单页（MainLayout hideTabBar），无导航可点，回首页继续
      const home = [...document.querySelectorAll('a[href="/"]')][0];
      if (home) { home.click(); return 'no-tab-for:' + target; }
      if (location.pathname === '/publish' && history.length > 1) { history.back(); return 'back'; }
      return 'NO_NAV_EL';
    })()`,
    returnByValue: true,
  })
  await sleep(1100)
  return nav?.result?.value
}

async function run() {
  const { p, ws, send, consoleErrors } = await launch()
  try {
    const login = await loginAndInject(send)
    console.log('登录注入:', login)
    if (!String(login).startsWith('OK')) {
      console.log('!! 登录失败，跳过路由检查（这不是被测对象，登录问题另报）')
      return
    }

    let bad = 0
    let lastUrl = ''
    for (const r of ROUTES) {
      const how = await gotoRoute(send, r.path)
      const m = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
      const v = m?.result?.value
      if (!v || !v.found) { console.log(`  [FAIL] ${r.name.padEnd(12)} 找不到 main/.content 容器`); bad++; continue }
      const ok = v.hasContent

      /* [注] 关键硬断言：URL 必须真的变了。
       *   第二版在这里报了假绿：Chat / Notify / Home(回) 三行显示的 url 全是
       *   /publish、kids 全是 41 —— 页面压根没导航过，量的是同一个页面，
       *   而「那个页面有内容」所以判 ok。三行假 PASS。
       *
       *   「有内容」和「这是正确的那个页面」是两件事，必须分开判。
       */
      const urlOk = v.url === r.path
      /**
       * 两个失败原因**都要**累加 bad,缺一个结论就会与逐行输出矛盾。
       *
       * 实测踩过:这里原本只累加 `!urlOk`,漏了 `!ok`(白屏)。
       * 于是注入 out-in 后屏幕上 4 行 `[FAIL] kids=0 text=0`,
       * 最后一行却打印「结论: PASS」——**白屏没被算进 bad**。
       *
       * 「逐行说 FAIL」和「总结说 PASS」同时出现时,
       * 唯一的解释是累加逻辑漏了一项,而不是其中一行看错了。
       */
      if (!urlOk) bad++
      if (!ok) bad++
      const flag = ok && urlOk ? ' ok ' : 'FAIL'
      console.log(`  [${flag}] ${r.name.padEnd(12)} 期望=${r.path.padEnd(12)} 实际=${String(v.url).padEnd(12)} kids=${String(v.kids).padEnd(5)} text=${String(v.text).padEnd(5)} nav=${how === 'NO_NAV_EL' ? '没点上' : how}`)
      if (!urlOk) console.log(`         [注意] URL 没变——这不是「页面正常」，是「根本没导航成功」。量测结果无效。`)
    }

    console.log(`\n控制台致命错误: ${consoleErrors.length} 条`)
    for (const e of consoleErrors.slice(0, 8)) console.log('   [错误]', e.replace(/\n/g, ' ').slice(0, 150))

    const pass = bad === 0 && consoleErrors.length === 0
    console.log(`\n结论: ${pass ? 'PASS 无白屏、无致命错误' : `FAIL（${bad} 个白屏 / ${consoleErrors.length} 条致命错误）`}`)
    /**
     * 退出码必须跟着结论走。
     *
     * 实测踩过:把 `mode="out-in"` 注回去后,输出里有 4 行 `[FAIL] kids=0 text=0`,
     * 但最后一行仍打印「结论: PASS」——**因为只改了打印,没改 exitCode**。
     * 那样 CI 跑完是绿的,门禁形同不存在,而「屏幕上明明有 4 个 FAIL」。
     *
     * 打印给人看,退出码给机器判。两个都要,否则就是「报全部通过」和
     * 「检查项根本没在跑」长得一样。
     */
    if (!pass) process.exitCode = 1
    process.exitCode = pass ? 0 : 1
  } finally {
    try { ws.close() } catch {}
    p.kill()
  }
}

/* ── 灵敏度验证：先证明判据真能抓到白屏，否则上面的 PASS 不值钱 ── */
async function selfTest() {
  console.log('灵敏度自检：注入已知白屏，验证判据能否抓到\n')
  const { p, ws, send } = await launch()
  try {
    await send('Page.navigate', { url: `${BASE}/` })
    await sleep(3000)

    // [注] 自检不依赖登录态：先在 body 里造一个 main 作为量测容器，
    //   否则未登录时页面根本不渲染 main，四个用例全落在 found:false 分支，
    //   判据等于没被测过（这是我第一版的 bug，frontend-2 测出来的）。
    await send('Runtime.evaluate', {
      expression: `(() => {
        let m = document.querySelector('main');
        if (!m) { m = document.createElement('main'); m.id = '__selftest_main'; document.body.appendChild(m); }
        window.__selftestMain = m;
        return 1;
      })()`,
    })
    await sleep(200)

    const cases = [
      { label: '正常页面（有文字，应判 ok）', js: `window.__selftestMain.innerHTML = '<p>hello</p>'`, expect: 'ok' },
      { label: '塞入 svg（无文字也算有内容）', js: `window.__selftestMain.innerHTML = '<svg width="10" height="10"></svg>'`, expect: 'ok' },
      { label: '完全清空（应判白屏）', js: `window.__selftestMain.innerHTML = ''`, expect: 'blank' },
      { label: '注入 8px 空 div（有高度但无内容，应判白屏）', js: `window.__selftestMain.innerHTML = '<div style="height:8px"></div>'`, expect: 'blank' },
      { label: '只有注释（应判白屏）', js: `window.__selftestMain.innerHTML = '<!-- x -->'`, expect: 'blank' },
    ]

    let bad = 0
    for (const c of cases) {
      await send('Runtime.evaluate', { expression: c.js })
      await sleep(200)
      const m = await send('Runtime.evaluate', { expression: MEASURE, returnByValue: true })
      const v = m?.result?.value
      const got = v && v.found && v.hasContent ? 'ok' : 'blank'
      const ok = got === c.expect
      if (!ok) bad++
      console.log(`  [${ok ? ' ok ' : 'FAIL'}] ${c.label.padEnd(44)} 期望=${c.expect.padEnd(5)} 实际=${got.padEnd(5)} kids=${v?.kids} text=${v?.text} media=${v?.media}${v?.why ? ' why=' + v.why : ''}`)
    }
    console.log(`\n自检 ${bad === 0 ? '全部符合预期' : `有 ${bad} 项判据本身不可靠`}`)
    process.exitCode = bad === 0 ? 0 : 1
  } finally {
    try { ws.close() } catch {}
    p.kill()
  }
}

if (process.argv.includes('--self-test')) await selfTest()
else await run()
