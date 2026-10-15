/**
 * 门禁清单与自检 —— **不执行任何门禁**，只做两件事：
 *   1. 校验清单里的每个脚本都真实存在（缺一个就失败）
 *   2. 打印逐条执行命令，供人复制或供 CI 使用
 *
 * ★ 为什么改成「不执行」：
 *   第一版在这里 spawnSync 执行所有门禁，结果 8 个全部退出码 -1、输出全空。
 *   查下来是**本机沙箱禁止嵌套创建进程**——连
 *   `spawnSync('node x.js')` 和 `spawnSync('python x.py')` 都返回
 *   status=null（进程根本没起来），而 npm 内部同样要 spawn 却能跑通。
 *   也就是说：只要用 node 去拉别的进程，就全灭。
 *
 *   而「8 个 FAIL + 无输出」这种结果比报错更危险——
 *   它看起来像「所有门禁都坏了」，实际一个门禁都没跑过。
 *   于是有人会去改本来正确的代码。
 *
 *   所以执行交给 npm（见 package.json 的 check:all），本脚本只做清单校验。
 *   **一个不能在本环境验证的检查，不如没有**——它给的是虚假的安心。
 *
 * 用法：node scripts/check-all.mjs
 */
import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const PROJECT = resolve(ROOT, '..')

/** 门禁清单。exec 字段是给 package.json 用的相对路径。 */
const GATES = [
  { name: 'check-sfc', file: 'scripts/check-sfc.mjs', cwd: ROOT, exec: 'node scripts/check-sfc.mjs', what: 'SFC 编译器零提醒' },
  { name: 'check-template-refs', file: 'scripts/check-template-refs.mjs', cwd: ROOT, exec: 'node scripts/check-template-refs.mjs', what: '模板标识符存在性' },
  { name: 'check-external-css', file: 'scripts/check-external-css.mjs', cwd: ROOT, exec: 'node scripts/check-external-css.mjs', what: '外置样式存在性 + 括号平衡' },
  { name: 'check-composable-exports', file: 'scripts/check-composable-exports.mjs', cwd: ROOT, exec: 'node scripts/check-composable-exports.mjs', what: 'composable 解构契约' },
  { name: 'check-undefined-identifiers', file: 'scripts/check-undefined-identifiers.mjs', cwd: ROOT, exec: 'node scripts/check-undefined-identifiers.mjs', what: '漏 import / 用错名字' },
  { name: 'verify-frontend', file: 'scripts/verify-frontend.py', cwd: ROOT, exec: 'python scripts/verify-frontend.py', what: 'P0 门禁 + Token 纪律' },
  // ★ 这三条在项目根的 scripts/ 下。第一版写成 cwd:PROJECT + file:'../scripts/x.py',
  //   两个 '..' 抵消，路径跑到 Desktop\scripts\ 下 —— 报「脚本不存在」，
  //   而它们其实存在。路径断言也要自己测自己。
  { name: 'verify-openapi', file: 'scripts/verify-openapi.py', cwd: PROJECT, exec: 'python scripts/verify-openapi.py', what: '契约可解析 + $ref 零悬空' },
  { name: 'verify-contract', file: 'scripts/verify-contract.py', cwd: PROJECT, exec: 'python scripts/verify-contract.py', what: '契约与 Java VO 字段一致' },
  { name: 'verify-datetime-format', file: 'scripts/verify-datetime-format.py', cwd: PROJECT, exec: 'python scripts/verify-datetime-format.py', what: '日期字段带 date-time' },
  { name: 'verify-studentno-parse', file: 'scripts/verify-studentno-parse.py', cwd: PROJECT, exec: 'python scripts/verify-studentno-parse.py', what: '学号解析规则与 5 个真实样本一致' },
]

console.log('='.repeat(72))
console.log('门禁清单校验（本脚本不执行门禁，只校验清单并打印执行方式）')
console.log('='.repeat(72))

const missing = []
for (const g of GATES) {
  const p = resolve(g.cwd, g.file)
  const ok = existsSync(p)
  if (!ok) missing.push(g)
  console.log(`  [${ok ? '有' : '★缺'}] ${g.name.padEnd(30)} ${g.what}`)
}

console.log('')
if (missing.length) {
  console.log(`★ ${missing.length} 个门禁脚本不存在：${missing.map((m) => m.name).join(', ')}`)
  console.log('  缺脚本必须判失败，不能跳过后继续报「门禁通过」——')
  console.log('  那会让「没检查」看起来像「检查了没问题」。')
  process.exit(2)
}

console.log(`清单完整：${GATES.length} 个门禁脚本全部就位`)
console.log('')
console.log('逐条执行（在 frontend/ 目录下）：')
for (const g of GATES) console.log(`  cd ${g.cwd === ROOT ? 'frontend' : '项目根'} && ${g.exec}`)
console.log('')
console.log('或一条命令跑全部（由 Python 执行器串联，任何一条失败都不中断其余门禁）：')
console.log('  npm run check:all')
console.log('')
console.log('★ 为何不由本脚本执行：本机沙箱禁止 node拉起子进程')
console.log('  （spawnSync 任何命令都返回 status=null），实测会得到')
console.log('  「所有门禁全FAIL 且无输出」——那是假失败，不是真问题。')
console.log('  python 的 subprocess 不受此限制，所以执行器是 scripts/run-all-gates.py。')
console.log('')
console.log('★ 本清单与 scripts/run-all-gates.py 的 GATES 必须一致：')
console.log('  本脚本只校验「脚本存在」，执行器负责真正跑。改一处请同步另一处。')
process.exit(0)
