# -*- coding: utf-8 -*-
"""校验前端 web 热修包（可在 Windows 上模拟 Linux sha256sum -c）。

用途：
    python scripts/verify-web-package.py .e2e-env/deploy/xxx.zip

检查项：
1. SHA256SUMS.txt 必须是 LF 行尾；带 CRLF 会被 Linux 的 sha256sum 当成
   文件名的一部分，表现为每个文件都 "No such file or directory"。
2. SHA256SUMS.txt 里每个文件的 sha256 与 zip 内字节一致。
3. web/index.html 的入口 JS 存在，且与 web/assets-manifest.txt 第一行一致。
"""
import hashlib
import re
import sys
import zipfile
from pathlib import Path

SUMS = 'SHA256SUMS.txt'
ENTRY_RE = re.compile(r'assets/(index-[A-Za-z0-9_-]+\.js)')


def fail(msg):
    print(f'FAIL: {msg}', file=sys.stderr)
    raise SystemExit(1)


def main(zip_path):
    path = Path(zip_path)
    if not path.is_file():
        fail(f'包不存在: {path}')

    with zipfile.ZipFile(path) as z:
        names = set(z.namelist())
        if SUMS not in names:
            fail(f'缺少 {SUMS}')
        raw = z.read(SUMS)
        if b'\r' in raw:
            fail(f'{SUMS} 含 CRLF；Linux sha256sum 会把 \\r 当成文件名的一部分')

        try:
            text = raw.decode('utf-8')
        except UnicodeDecodeError as e:
            fail(f'{SUMS} 不是 UTF-8: {e}')

        checked = 0
        for line in text.split('\n'):
            if not line.strip():
                continue
            parts = line.split(' ', 1)
            if len(parts) != 2 or len(parts[0]) != 64:
                fail(f'{SUMS} 行格式错误: {line[:80]}')
            digest, rel = parts[0], parts[1]
            if rel.startswith('*'):
                rel = rel[1:]
            elif rel.startswith(' '):
                rel = rel[1:]
            else:
                fail(f'{SUMS} 行格式错误(缺少 * 或空格): {line[:80]}')
            if rel not in names:
                fail(f'清单中的文件不在 zip 内: {rel}')
            actual = hashlib.sha256(z.read(rel)).hexdigest()
            if actual != digest:
                fail(f'哈希不一致: {rel}')
            checked += 1

        if 'web/index.html' not in names:
            fail('缺少 web/index.html')
        html = z.read('web/index.html').decode('utf-8')
        entry = ENTRY_RE.search(html)
        if not entry:
            fail('web/index.html 未找到 index-*.js 入口')
        entry = entry.group(1)
        if f'web/assets/{entry}' not in names:
            fail(f'入口文件不在 zip 内: web/assets/{entry}')

        if 'web/assets-manifest.txt' not in names:
            fail('缺少 web/assets-manifest.txt')
        manifest = z.read('web/assets-manifest.txt').decode('utf-8').splitlines()
        if not manifest or manifest[0] != entry:
            fail(f'assets-manifest.txt 第一行应为 {entry}，实际为 {manifest[:1]}')

    print(f'OK: {checked} 个文件哈希一致；入口 {entry}；行尾 LF')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        print('用法: python scripts/verify-web-package.py <zip>', file=sys.stderr)
        raise SystemExit(2)
    main(sys.argv[1])
