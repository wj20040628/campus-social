import os, re, sys
from collections import Counter

sys.stdout.reconfigure(encoding='utf-8')
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = {'node_modules', 'dist', '.git', 'target', '.workbuddy'}


def walk(exts, base):
    for root, dirs, files in os.walk(base):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for f in files:
            if f.endswith(exts):
                yield os.path.join(root, f)


files = list(walk(('.vue', '.js', '.mjs', '.css'), ROOT))

# 自检：扫不到文件就退出，绝不继续报「命中 0 → 通过」。
# 本脚本第一版把 ROOT 写成 scripts/，只扫到 4 个 .mjs/.py，
# 于是 A1~A5 与 D 组全部报「命中 0」——看起来全绿，实际一个 .vue 都没读。
# 「扫描类检查」最危险的失败模式不是报错，是扫了个空目录然后告诉你没问题。
assert len(files) > 50, '自检失败：只扫到 %d 个文件（ROOT=%s），检查无意义' % (len(files), ROOT)
assert any(f.endswith('.vue') for f in files), '自检失败：一个 .vue 都没扫到'
print('自检通过：扫到 %d 个源文件，其中 .vue %d 个'
      % (len(files), sum(1 for f in files if f.endswith('.vue'))))


def strip_comments(text):
    """把 CSS/JS 注释替换成等长空格，保证行号不变。"""
    def blank(m):
        return re.sub(r'[^\n]', ' ', m.group(0))
    text = re.sub(r'/\*.*?\*/', blank, text, flags=re.S)
    text = re.sub(r'<!--.*?-->', blank, text, flags=re.S)
    text = re.sub(r'(?<![:"\'\w])//[^\n]*', blank, text)
    return text


def scan(pat, label, show=6, ignore_files=()):
    rx = re.compile(pat)
    hits = []
    for p in files:
        if any(f in p for f in ignore_files):
            continue
        s = open(p, encoding='utf-8').read()
        hay = strip_comments(s)
        raw = s.split('\n')
        for i, l in enumerate(hay.split('\n'), 1):
            if rx.search(l):
                hits.append((os.path.relpath(p, ROOT), i, raw[i - 1].strip()[:88]))
    print('=== %s ===  命中 %d' % (label, len(hits)))
    for h in hits[:show]:
        print('  %s:%d  %s' % (h[0], h[1], h[2]))
    if len(hits) > show:
        print('  ... 另 %d 处' % (len(hits) - show))
    return hits


print()
print('【A 组：P0 门禁，注释剔除后只看真实代码】')
scan(r'@media[^{]*\(--ys-bp', 'A1. @media(--ys-bp-*) —— 浏览器静默丢弃整块规则', 4)
scan(r'<el-col[^>]*\s:(xs|sm|md|lg|xl)=', 'A2. el-col 断点 props（EP 与设计稿错位）', 4)
scan(r"from\s+['\"]element-plus['\"]", "A3. 直接 from element-plus（TD-21 全包内联根因）", 4)
scan(r'[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF\uFE0F]', 'A4. emoji', 4)
scan(r'cubic-bezier\([^)]*\b(?:1\.7|2\.[0-9]|[3-9]\.)', 'A5. 弹跳/过冲缓动', 4)

print()
print('【B 组：设计 token 纪律】')
# design-tokens.css 是 token 定义处，硬编码颜色合法
TOKEN = ('design-tokens.css',)
color_hits = scan(r'#[0-9a-fA-F]{3,8}\b', 'B1. 硬编码颜色（已排除 token 定义文件）', 6, TOKEN)
# design-tokens.css:11 契约：业务代码禁止硬编码色值，唯一例外 #fff / #000
exc_rx = re.compile(r'#(?:fff|ffffff|000|000000)\b', re.I)
real = [h for h in color_hits if not exc_rx.search(h[2])]
print('    其中属契约允许的例外（#fff/#000）：%d 处' % (len(color_hits) - len(real)))
print('    真正待处理的硬编码：%d 处' % len(real))
for h in real:
    print('★ %s:%d  %s' % h)

print()
print('【C 组：@media 断点字面量】')
rx = re.compile(r'@media[^{]*?\(\s*min-width:\s*(\d+)px\s*\)')
c = Counter()
for p in files:
    hay = strip_comments(open(p, encoding='utf-8').read())
    for m in rx.finditer(hay):
        c[m.group(1)] += 1
for k, v in sorted(c.items(), key=lambda x: int(x[0])):
    print('  %5spx -> %d 处' % (k, v))
ALLOWED = {'640', '768', '1024', '1280', '1536'}
bad = {k: v for k, v in c.items() if k not in ALLOWED}
print('  合计 %d 处；不在设计稿断点 %s 内的：%s'
      % (sum(c.values()), sorted(ALLOWED, key=int), bad if bad else '无'))

print()
print('【D 组：token 唯一准绳（ADR-009）】')
n_files = 0
n_refs = 0
for p in walk(('.css',), ROOT):
    s = open(p, encoding='utf-8').read()
    n = len(re.findall(r'var\(\s*--ys-', s))
    if n:
        n_files += 1
        n_refs += n
print('  %d 个 CSS 文件，共 %d 处 var(--ys-*)' % (n_files, n_refs))