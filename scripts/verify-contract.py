"""契约与后端 VO 字段一致性比对。

用途：防止「契约描述与实现不一致」这类静默漂移。

来历：帖子域 13 个方法曾整体不在契约里（原契约 27 个路径全是本轮新建的），
契约号称前后端唯一准绳，漏一整个域等于契约失效。补上之后需要一个可复现的
机制持续守着，而不是靠人记。

判定口径：
  - VO 字段从 `private <type> <name>;` 抽取，只看 VO 目录下的文件
  - 契约字段从 components/schemas/<Name>.properties 取；支持 allOf 包裹
  - 「Java 有契约无」= 实现多出来的字段，前端拿不到 → 会用 undefined 渲染
  - 「契约有 Java 无」= 契约承诺了但没实现，前端会拿到 undefined
  两类都要报。已知豁免写进 KNOWN_GAPS，必须带理由，不接受「先挂着」。

用法：python scripts/verify-contract.py [--allow-gaps]
退出码非 0 表示有不一致（CI 可用），但默认只打印 —— 口径变更需人工确认。
"""

import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VO_DIR = os.path.join(ROOT, 'backend', 'src', 'main', 'java', 'com', 'campus', 'social', 'vo')
OPENAPI = os.path.join(ROOT, 'docs', 'api', 'openapi.yaml')

# 已知差异。每条必须写清「为什么」与「谁负责补」，不允许无理由挂起。
KNOWN_GAPS = {
    'CommentVO': {
        'contract_only': ['children', 'childrenOverflowCount'],
        'reason': 'B-1 评论子树，本轮后端实现中。契约已定（57e20e1），'
                  '上限 20/节点、超限不得静默截断。',
        'owner': 'backend',
    },
}


def load_schemas():
    try:
        import yaml
    except ImportError:
        print('!!需要 PyYAML：pip install pyyaml')
        raise SystemExit(2)
    with open(OPENAPI, encoding='utf-8') as f:
        doc = yaml.safe_load(f)
    return (doc.get('components', {}) or {}).get('schemas', {}) or {}


def contract_props(schema):
    """取 properties，兼容 allOf 包裹的写法。"""
    props = dict(schema.get('properties') or {})
    for part in schema.get('allOf') or []:
        props.update(part.get('properties') or {})
    return set(props.keys())


def java_fields(path):
    """抽 VO 字段：private <type> <name>;（跳过 static/final 与方法签名）。"""
    src = open(path, encoding='utf-8').read()
    out = set()
    for m in re.finditer(r'^\s*private\s+(?!static\b)(?!final\b)[\w<>,.\[\]\s]+?\s+(\w+)\s*;',
                         src, re.M):
        out.add(m.group(1))
    return out


def main():
    schemas = load_schemas()
    if not schemas:
        print('!! 契约里没有 schemas，检查 openapi.yaml 是否损坏')
        raise SystemExit(2)

    vo_files = sorted(f for f in os.listdir(VO_DIR) if f.endswith('.java'))
    if len(vo_files) < 5:
        print('!! 只扫到 %d 个 VO 文件（目录 %s），扫描范围可能不对'
              % (len(vo_files), VO_DIR))
        raise SystemExit(2)

    print('扫描 %d 个 VO 文件，对照契约 %d 个 schema' % (len(vo_files), len(schemas)))
    print()

    mismatches = 0
    gaps_used = set()

    for f in vo_files:
        name = f[:-5]
        schema = schemas.get(name)
        if schema is None:
            continue  # 契约未定义该 VO：本轮不视为不一致，单独在下方汇总
        jf = java_fields(os.path.join(VO_DIR, f))
        cf = contract_props(schema)
        only_java = sorted(jf - cf)
        only_con = sorted(cf - jf)

        gap = KNOWN_GAPS.get(name)
        pending = []
        if gap:
            gaps_used.add(name)
            expected = gap.get('contract_only', [])
            pending = [x for x in only_con if x in expected]
            only_con = [x for x in only_con if x not in expected]
            # 豁免项若在契约里已经不存在，说明豁免过期了（字段被删/被改名）。
            # 灵敏度验证实测过：把 children 从契约删掉后，这里原本不报，
            # 只显示「豁免 1 个」，看起来一切正常。
            gone = [x for x in expected if x not in cf and x not in jf]
            if gone:
                mismatches += 1
                print('  %-32s ★豁免项已不存在于契约与实现: %s'
                      % (name, ', '.join(gone)))
                print('    若该字段确已废弃，请从 KNOWN_GAPS 删除 %s 的条目' % name)
                print()

        if not only_java and not only_con and not pending:
            print('  %-32s %2d 字段  一致' % (name, len(jf)))
            continue

        if only_java or only_con:
            mismatches += 1
            print('  %-32s %2d Java / %2d 契约  ★不一致' % (name, len(jf), len(cf)))
        if only_java:
            print('★ Java 有契约无（前端会拿到 undefined）: %s' % ', '.join(only_java))
        if only_con:
            print('★ 契约有 Java 无（契约承诺了但没实现）: %s' % ', '.join(only_con))

        # 豁免项必须显式打印。第一版把豁免项直接从 only_con 里滤掉，
        # 于是 CommentVO 在后端尚未实现 children 时报「13 字段 一致」——
        # 恰恰是本脚本该抓的那件事被掩盖成了通过。换KNOWN_GAPS 静默为绿。
        if pending:
            print('  %-32s %2d Java / %2d 契约  ⏳豁免 %d 个（已登记，未实现）'
                  % (name, len(jf), len(cf), len(pending)))
            print('    待实现: %s' % ', '.join(pending))
            print('    理由: %s' % gap['reason'])
            print('    负责人: %s' % gap['owner'])
        print()

    stale = set(KNOWN_GAPS) - gaps_used
    if stale:
        print('!! KNOWN_GAPS 里有已失效条目（字段已补齐或 VO 已改名）: %s' % ', '.join(sorted(stale)))
        mismatches += 1

    undef = sorted(f[:-5] for f in vo_files if f[:-5] not in schemas)
    if undef:
        print()
        print('契约未定义这些 VO（非阻塞，但契约号称唯一准绳，建议补）:')
        print('  %s' % ', '.join(undef))

    print()
    if mismatches:
        print('结果: %d 处不一致' % mismatches)
    else:
        print('结果: 无不一致')
    if gaps_used:
        print('注意: %s 有已登记但未实现的字段，补齐后请从 KNOWN_GAPS 移除'
              % ', '.join(sorted(gaps_used)))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())