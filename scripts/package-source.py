# -*- coding: utf-8 -*-
"""打「源代码提交包」：只收源码与文档，剔除构建产物、依赖、真实数据与任何密钥。

用法：python scripts/package-source.py [输出zip路径]
默认输出：F:/校园社交/源代码-友声校园社交平台.zip
"""
import os
import re
import sys
import zipfile

ROOT = r'F:\校园社交'
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, '源代码-友声校园社交平台.zip')

INCLUDE_DIRS = ['backend/src', 'backend/scripts', 'backend/sql', 'frontend/src', 'frontend/public',
                'frontend/scripts', 'frontend/docs', 'sql', 'scripts', 'docs',
                '.github/workflows']
INCLUDE_FILES = ['backend/pom.xml', 'backend/Dockerfile', 'frontend/index.html', 'frontend/package.json',
                 'frontend/package-lock.json', 'frontend/vite.config.js', 'frontend/nginx.conf',
                 'frontend/Dockerfile.frontend', 'frontend/auto-imports.d.ts', 'frontend/components.d.ts',
                 'frontend/.dockerignore', 'backend/.dockerignore', 'docker-compose.yml',
                 'README.md', 'LICENSE', '源代码说明.md', 'deploy.ps1', '.env.example', '.gitignore']

# 目录名/文件名/后缀黑名单——依赖、产物、真实上传数据、IDE 与临时脚本
EXCLUDE_DIR_NAMES = {'node_modules', 'target', 'dist', 'uploads', '__pycache__', '.idea', '.vscode',
                     '.git', '.e2e-env', '.e2e-shots', '.design-shots', '.report-build', '.report-shots',
                     'report', 'gui-test-screenshots', 'vibe_images', 'screens', 'design', 'report-figures',
                     '.claude', '.workbuddy'}
EXCLUDE_NAMES = {'application-local.yml', 'cp.txt'}
EXCLUDE_SUFFIXES = ('.docx', '.pdf', '.png', '.jpg', '.jpeg', '.gif', '.webp', '.mp4', '.jar', '.war',
                    '.timestamp-1793198650914-efd95f0107d9d.mjs')
EXCLUDE_PATTERNS = (re.compile(r'^vite\.config\.js\.timestamp-'), re.compile(r'^backup_.*\.sql$'),
                    re.compile(r'\.pyc$'))

LOCAL_YML = os.path.join(ROOT, 'backend', 'src', 'main', 'resources', 'application-local.yml')


def excluded(name):
    if name in EXCLUDE_NAMES:
        return True
    if name.endswith(EXCLUDE_SUFFIXES):
        return True
    return any(p.search(name) for p in EXCLUDE_PATTERNS)


def walk(rel_dir):
    base = os.path.join(ROOT, rel_dir.replace('/', os.sep))
    if not os.path.isdir(base):
        return
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIR_NAMES]
        for f in filenames:
            if excluded(f):
                continue
            full = os.path.join(dirpath, f)
            yield full, os.path.relpath(full, ROOT).replace(os.sep, '/')


def real_secrets():
    """从本地密钥文件里抠出真实值，用于反向断言包里不含密钥。"""
    vals = set()
    if not os.path.exists(LOCAL_YML):
        return vals
    for line in open(LOCAL_YML, encoding='utf-8'):
        m = re.match(r'\s*([a-z0-9-]*(?:secret|password|key|token)[a-z0-9-]*)\s*:\s*(\S.*)$', line, re.I)
        if not m:
            continue
        v = m.group(2).strip().strip('"\'')
        if len(v) >= 8 and not v.startswith('${'):
            vals.add(v)
    return vals


def main():
    files = {}
    for d in INCLUDE_DIRS:
        for full, rel in walk(d):
            files[rel] = full
    for f in INCLUDE_FILES:
        full = os.path.join(ROOT, f.replace('/', os.sep))
        if os.path.isfile(full) and not excluded(os.path.basename(f)):
            files[f] = full

    secrets = real_secrets()
    hits = []
    for rel, full in sorted(files.items()):
        if os.path.getsize(full) > 4 * 1024 * 1024:
            continue
        try:
            txt = open(full, encoding='utf-8').read()
        except (UnicodeDecodeError, OSError):
            continue
        for s in secrets:
            if s in txt:
                hits.append((rel, s[:6] + '…'))

    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel, full in sorted(files.items()):
            z.write(full, '友声校园社交平台-源代码/' + rel)

    n = len(files)
    size = os.path.getsize(OUT)
    print('files=%d  zip=%.2fMB  out=%s' % (n, size / 1048576, OUT))
    print('secret_scan=%s' % ('CLEAN' if not hits else 'LEAK ' + str(hits[:5])))
    return 1 if hits else 0


if __name__ == '__main__':
    sys.exit(main())
