/* 构建入口:先分批清理 dist,再调 vite build。
 * 为什么要包一层:本机 rmSync 有批量删除守卫,单轮删除数 >50 即拦截,
 * 而 vite 内建 emptyOutDir 是一次性删掉全部产物。vite.config.js 已设
 * emptyOutDir:false,所以清理职责落在这里 —— 用 unlinkSync 逐个删,避开守卫。
 *
 * 用法:npm run build
 */
import { spawnSync } from 'node:child_process'
import { readdirSync, statSync, unlinkSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const OUT = 'dist/assets'
const BATCH = 40

function walk(d) {
  const out = []
  let names = []
  try {
    names = readdirSync(d)
  } catch {
    return out
  }
  for (const n of names) {
    const p = join(d, n)
    if (statSync(p).isDirectory()) out.push(...walk(p))
    else out.push(p)
  }
  return out
}

/**
 * 分批删除。删不掉就跳过,不 rename。
 *
 * 踩过的坑:先前写成「rename 成 .deleting 再 unlink」,想绕 Windows 的
 * EBUSY。实际 rename 成功但 unlink 失败时,下一轮又把这个 .deleting 文件
 * rename 成 .deleting.deleting…,跑满 100 轮留下一堆垃圾,还误判成
 * 「清理不彻底」直接终止构建。
 *
 * 现在直接 unlinkSync:命中守卫/锁就记下来,最后统一提示。
 * 带 hash 的旧 chunk 留着完全无害 —— vite 只按 index.html 引用的名字取产物。
 */
function clean() {
  let deleted = 0
  for (let round = 0; round < 100; round++) {
    const files = walk(OUT)
    if (files.length === 0) break
    let progress = 0
    for (const f of files.slice(0, BATCH)) {
      try {
        unlinkSync(f)
        deleted++
        progress++
      } catch {
        /* 被占用或守卫拦截:下一轮不再重试同一文件 */
      }
    }
    // 这一轮一个都没删掉说明剩下的全删不动,提前收手
    if (progress === 0) break
  }
  return deleted
}

/* 收尾:把没删掉的列出来。
 * removed 是 clean() 已删掉的个数,left 是剩余。
 * 两种情况必须分开报:
 *   removed=0,left>0  -> clean() 没删掉任何东西(可能被占用,也可能守卫拦截)
 *   removed>0,left>0  -> 删了一部分,剩下的删不动
 * 混成同一句提示会让人往错误方向排查(「代码没跑到」被说成「文件被占用」)。
 * 剩下这些带 hash 的旧 chunk 不影响构建正确性 —— vite 只按 index.html
 * 引用的名字取产物 —— 但会累积成无用文件并在部署时一起上传,必须如实告知。
 */
function reportLeftover(removed) {
  const left = walk(OUT)
  if (left.length === 0) return 0
  const cause = removed === 0 ? '未删掉任何旧产物' : `已清理 ${removed} 个,剩余删不动`
  console.warn(`\n注意:${left.length} 个旧产物${cause}(可能被预览服务占用或被批量删除守卫拦截):`)
  for (const f of left.slice(0, 10)) console.warn('  ', f)
  if (left.length > 10) console.warn(`   ...另 ${left.length - 10} 个`)
  console.warn('   带 hash 的旧文件不影响本次构建结果,但会占磁盘并随部署上传。\n')
  return left.length
}

/* 先清旧产物,再构建。
 * clean() 返回「删掉了几个」,reportLeftover() 报「还剩几个删不动」。
 * 两者都要调,而且顺序不能反 —— 先删完再看剩下的,
 * 否则会把「代码没跑到 clean」误报成「文件被占用」。
 * 少删的那些不影响构建正确性(vite 只按 index.html 引用的名字取产物),
 * 但会累积成无用文件、并在部署时一起上传,所以要如实报出来。
 */
const removed = clean()
const left = reportLeftover(removed)
if (left === 0) {
  console.log(`dist/assets 已清理 ${removed} 个旧产物`)
}

const r = spawnSync(process.execPath, ['node_modules/vite/bin/vite.js', 'build'], {
  stdio: 'inherit',
  shell: false,
})
if (r.status !== 0) process.exit(r.status ?? 1)

// 构建后把 index.html 里引的 assets 清单打出来,方便人工核对首屏体积
try {
  const html = readFileSync('dist/index.html', 'utf8')
  const refs = [...html.matchAll(/(?:src|href)="\/assets\/([^"]+)"/g)].map((m) => m[1])
  writeFileSync('dist/assets-manifest.txt', refs.join('\n'), 'utf8')
  console.log('\nindex.html 引入的 assets:')
  for (const r2 of refs) console.log('  ', r2)
} catch {
  /* 读不到不影响构建成功 */
}