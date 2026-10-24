import { chromium } from 'file:///F:/%E6%A0%A1%E5%9B%AD%E7%A4%BE%E4%BA%A4/.e2e-env/node_modules/playwright/index.mjs'
const b = await chromium.launch({ channel: 'chrome' })
const p = await b.newPage()
const H = { Accept: 'application/vnd.github+json' }
const r = await p.request.get('https://api.github.com/repos/wj20040628/campus-social/actions/runs?per_page=1', { headers: H, timeout: 30000 })
const run = (await r.json()).workflow_runs[0]
console.log(`run #${run.run_number}  ${run.head_sha.slice(0,7)}  ${run.status}/${run.conclusion || '-'}`)
const rj = await p.request.get(`https://api.github.com/repos/wj20040628/campus-social/actions/runs/${run.id}/jobs`, { headers: H, timeout: 30000 })
for (const j of (await rj.json()).jobs || []) {
  console.log(`\n### ${j.name}  ${j.status}/${j.conclusion || '-'}`)
  for (const s of j.steps || []) console.log(`   ${(s.conclusion || s.status).padEnd(11)} ${s.name}`)
}
const rc = await p.request.get(`https://api.github.com/repos/wj20040628/campus-social/commits/${run.head_sha}/check-runs`, { headers: H, timeout: 30000 })
for (const cr of ((await rc.json()).check_runs) || []) {
  if (cr.output?.annotations_url) {
    const ra = await p.request.get(cr.output.annotations_url, { headers: H, timeout: 30000 })
    if (ra.ok()) for (const a of await ra.json()) if (a.annotation_level === 'failure') console.log(`  [FAIL] ${a.message.slice(0,200)}`)
  }
}
await b.close()
