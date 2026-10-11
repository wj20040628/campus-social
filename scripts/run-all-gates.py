 #!/usr/bin/env python
# -*- coding: utf-8 -*-
"""门禁一键执行器（scripts/run-all-gates.py）

★ 为什么不用 `a && b && c` 串联：
  `&&` 会在第一个失败处中断，后面的门禁**根本没跑**，而输出只有一行报错，
  看不出还有 5 个没执行。更糟的是它给出的是「通过」这个假象。
  本脚本逐条执行、跑完全部再汇总，任何一条失败都不影响其余门禁运行。

★ 为什么用python 而不是 node：
  本机沙箱禁止 node拉起子进程（实测 spawnSync任何命令都返回 status=null，
  看起来像「所有门禁都坏了」，实际一个都没跑过）。
  python 的 subprocess 不受此限制，所以执行器用 python 写。

★ 清单与 frontend/scripts/check-all.mjs 的 GATES 必须保持一致。
  check-all.mjs 只校验「脚本存在」不执行，本脚本负责真正执行。改一处请同步另一处。

用法：
    python scripts/run-all-gates.py                # 跑全部
    python scripts/run-all-gates.py --self-test    # 灵敏度自检
"""
from __future__ import annotations

import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
FRONTEND = os.path.join(PROJECT, "frontend")

# (name, cwd, argv, 一句话说明)
GATES = [
    ("check-sfc", FRONTEND, ["node", "scripts/check-sfc.mjs"], "SFC 编译器零提醒"),
    ("check-template-refs", FRONTEND, ["node", "scripts/check-template-refs.mjs"], "模板标识符存在性"),
    ("check-composable-exports", FRONTEND, ["node", "scripts/check-composable-exports.mjs"], "composable 解构契约"),
    ("check-undefined-identifiers", FRONTEND, ["node", "scripts/check-undefined-identifiers.mjs"], "漏import / 用错名字"),
    ("verify-frontend", FRONTEND, ["python", "scripts/verify-frontend.py"], "P0 门禁 + Token 纪律"),
    ("verify-openapi", PROJECT, ["python", "scripts/verify-openapi.py"], "契约可解析 + $ref 零悬空"),
    ("verify-contract", PROJECT, ["python", "scripts/verify-contract.py"], "契约与 Java VO 字段一致"),
    ("verify-datetime-format", PROJECT, ["python", "scripts/verify-datetime-format.py"], "日期字段带 date-time"),
    ("verify-studentno-parse", PROJECT, ["python", "scripts/verify-studentno-parse.py"], "学号解析与 5 个真实样本一致"),
]

# 失败时每个门禁最多回显这么多行输出，避免刷屏
TAIL = 12


def run_one(name, cwd, argv, _what):
    """跑单个门禁，返回 (ok, 退出码, 输出文本)。不抛异常。"""
    try:
        p = subprocess.run(argv, cwd=cwd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=600)
    except FileNotFoundError as e:
        return False, -1, "命令不存在: %s (%s)" % (argv[0], e)
    except subprocess.TimeoutExpired:
        return False, -2, "超时（>600s）"
    out = (p.stdout or "") + (p.stderr or "")
    return p.returncode == 0, p.returncode, out


def run_all(gates=None, quiet=False):
    gates = gates or GATES
    results = []
    width = max(len(g[0]) for g in gates)
    for name, cwd, argv, what in gates:
        if not quiet:
            print("  运行 %-*s %s" % (width, name, what))
        ok, code, out = run_one(name, cwd, argv, what)
        results.append((name, ok, code, out))
        if not quiet and ok:
            print("       通过")
    return results


def report(results):
    failed = [r for r in results if not r[1]]
    print("=" * 72)
    print("门禁结果：%d 条，失败 %d 条" % (len(results), len(failed)))
    if failed:
        print("=" * 72)
        for name, _ok, code, out in failed:
            print("\n--- FAIL %s（退出码 %s）---" % (name, code))
            lines = [l for l in out.strip().splitlines() if l.strip()]
            for line in lines[-TAIL:]:
                print("    " + line)
    print("=" * 72)
    if failed:
        print("失败清单：" + "、".join(n for n, _, _, _ in failed))
        print("★ 其余门禁都已真实执行过，不是「被跳过了」。")
    return 1 if failed else 0


def self_test():
    """灵敏度自检：塞一条必然失败的假门禁 + 一条必然通过的假门禁。

    只证明「本执行器能如实报告失败与通过」，不证明各门禁本身有效
    —— 那是 check-undefined-identifiers / verify-studentno-parse 等
    各自 --self-test 的职责。
    """
    print("=" * 72)
    print("执行器灵敏度自检（注入一真一假两条门禁）")
    print("=" * 72)
    fake = [
        ("gate-true", PROJECT, ["python", "-c", "print('ok')"], "注入：必然通过"),
        ("gate-false", PROJECT, ["python", "-c", "import sys; sys.exit(3)"], "注入：必然失败"),
    ]
    results = run_all(fake)
    by_name = {r[0]: r for r in results}

    problems = []
    if by_name["gate-true"][1] is not True:
        problems.append("必然通过的假门禁被判失败")
    if by_name["gate-false"][1] is not False:
        problems.append("必然失败的假门禁被判通过——这是最危险的假绿")
    if by_name["gate-false"][2] != 3:
        problems.append("退出码未透传，实际拿到 %s" % by_name["gate-false"][2])
    if len(results) != 2:
        problems.append("门禁数量不对，应为 2，实际 %d" % len(results))

    print("-" * 72)
    for p in problems:
        print("FAIL " + p)
    if not problems:
        print("PASS 两条注入门禁的判定与退出码均如实反映")
        print("PASS 全部 2 条都真实执行，无一被跳过")
        return 0
    return 1


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    print("=" * 72)
    print("门禁一键执行（共 %d 条，任何一条失败都不会中断其余门禁）" % len(GATES))
    print("=" * 72)
    sys.exit(report(run_all()))