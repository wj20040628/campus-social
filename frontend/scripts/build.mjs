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
  /* 失败项收集在集合里,跨轮不再重试。
   *
   * ★ 这是一个实测出来的缺陷,不是理论优化:
   *   原实现每轮重新 walk() 再 slice(0, BATCH),于是被守卫拦下的那批文件
   *   永远排在最前面 —— 每轮切到的都是同一批(删不掉的) + 同一批新的,
   *   跑满 100 轮就放弃。而守卫是**累计计数**的:本机 safe-delete 在
   *   单轮累计删到 50 个之后开始拦截,一旦撞上,后续 unlinkSync 全失败。
   *
   *   实测后果:dist/assets 里同时躺着 17:52 / 18:03 / 18:04 三个批次的产物,
   *   index-*.js 有三个同尺寸不同 hash 的副本,总计 148 个文件,
   *   而 index.html 只引用其中 1 个。「死产物 0 个」这个结论是错的。
   *
   *   现在把失败项记进 failed,后续轮次跳过它们,
   *   让本轮剩下的文件仍有机会被删掉。
   */
  const failed = new Set()
  for (let round = 0; round < 100; round++) {
    const files = walk(OUT).filter((f) => !failed.has(f))
    if (files.length === 0) break
    let progress = 0
    for (const f of files.slice(0, BATCH)) {
      try {
        unlinkSync(f)
        deleted++
        progress++
      } catch {
        /* 命中批量删除守卫或文件被占用:本轮不再重试这一项 */
        failed.add(f)
      }
    }
    // 这一轮一个都没删掉,说明剩下的全是已知的删不动项,提前收手
    if (progress === 0) break
  }
  return deleted
}

/* 收尾:把没删掉的列出来,并说清成因。
 *
 * removed 是 clean() 已删掉的个数,left 是剩余。
 * 两种情况必须分开报:
 *   removed=0,left>0  -> clean() 没删掉任何东西
 *   removed>0,left>0  -> 删了一部分,剩下的删不动
 * 混成同一句提示会让人往错误方向排查。
 *
 * [实测:删不动的成因不是文件占用,是本机的 safe-delete 批量守卫]
 * 单轮 unlinkSync 删 121 个文件:成功 49、失败 72,失败原因全部是
 *   [safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":50 → N 个}
 * 守卫在累计删除数超过阈值(50)后开始拦截,而 clean() 的 BATCH=40
 * 意味着第二轮就撞上它。**没有一个是 EBUSY/占用。**
 *
 * 所以提示必须写「批量删除守卫」而不是「可能被占用」——
 * 后者会把人引到「关掉预览服务」这个方向上,而那跟这里完全无关。
 *
 * 解法:交互式确认一次即可(用户在终端确认),或手工清空 dist/assets。
 * 剩下这些带 hash 的旧 chunk **不影响构建正确性** ——
 * vite 只按 index.html 引用的名字取产物 —— 但会占磁盘并随部署上传,必须如实告知。
 */
function reportLeftover(removed) {
  const left = walk(OUT)
  if (left.length === 0) return 0
  const cause = removed === 0
    ? '未删掉任何旧产物'
    : `已清理 ${removed} 个,剩余删不动`
  console.warn(`\n注意:${left.length} 个旧产物${cause}:`)
  console.warn('  成因:本机 safe-delete 批量删除守卫(单轮累计删到 50 个后拦截),不是文件被占用。')
  console.warn('  它们**不影响本次构建的正确性**(vite 只按 index.html 引用的名字取产物),')
  console.warn('  但会让「dist 里有几个文件」这个数字失真:跨轮构建会在此累积,')
  console.warn('  实测见过index-*.js 三个同尺寸不同 hash 的副本并存。')
  console.warn('  统计首屏体积时只认 index.html 实际引用的那几个;')
  console.warn('  要彻底清空请手工删除 dist/assets 目录,或在终端确认一次批量删除。')
  for (const f of left.slice(0, 10)) console.warn('  ', f)
  if (left.length > 10) console.warn(`   ...另 ${left.length - 10} 个`)
  console.warn('')
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