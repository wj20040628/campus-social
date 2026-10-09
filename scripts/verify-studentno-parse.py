#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""学号解析规则门禁（scripts/verify-studentno-parse.py）

规则来源：5 个真实样本 + 教育部本科专业目录交叉验证。样本见 FIXTURES。

这不是文档，是可执行规格。后端 Java 实现必须通过本脚本列出的全部用例；
若实现与本脚本不一致，以本脚本为准（除非先改本脚本并说明理由）。

用法：
    python scripts/verify-studentno-parse.py            # 正常校验
    python scripts/verify-studentno-parse.py --self-test  # 灵敏度自检
"""
from __future__ import annotations

import os
import re
import sys

# 位 1-2 入学年份 | 位 3-6 专业类 | 位 7-8 专业序号 | 位 9-10 班级 | 位 11+ 流水号
# 长度区间 12-14 由正则本身约束（2+4+2+2+2~4），不另设判断。
PATTERN = re.compile(r"^(\d{2})(\d{4})(\d{2})(\d{2})(\d{2,4})$")

# 5 个真实样本。expected 为 None 表示「应解析失败」。
# class_no 必须按字符串比对：'02' 走正则捕获时保留前导零，转 int 会变成 '2'。
FIXTURES = [
    ("23080901060536", {
        "parsed": True, "entrance_year": 2023, "major_class_code": "0809",
        "major_code": "080901", "class_no": "06",
    }, "用户本人 23 级计算机科学与技术 6 班"),
    ("2408091003508", {
        "parsed": True, "entrance_year": 2024, "major_class_code": "0809",
        "major_code": "080910", "class_no": "03",
    }, "大数据 24-3 班；专业实为 080910T，学号不带 T"),
    ("2508021402549", {
        "parsed": True, "entrance_year": 2025, "major_class_code": "0802",
        "major_code": "080214", "class_no": "02",
    }, "智能车辆工程 2 班"),
    ("2508021401546", {
        "parsed": True, "entrance_year": 2025, "major_class_code": "0802",
        "major_code": "080214", "class_no": "01",
    }, "智能车辆工程 1 班；与 2 班同专业，班级位不同"),
    ("2408180102539", {
        "parsed": True, "entrance_year": 2024, "major_class_code": "0818",
        "major_code": "081801", "class_no": "02",
    }, "交通运输 24-2 班"),
]

# 应解析失败的输入。流���号长度不校验，因此 13/14 位都应成功；
# 真正的失败只有「非纯数字」和「长度出界」两类。
NEGATIVE = [
    ("", "空串"),
    ("23080901060", "11 位，短于下界"),
    ("230809010605361", "15 位，长于上界"),
    ("2308090106053a", "含非数字字符"),
    ("  23080901060536", "含空格"),
    ("2308090106053.6", "含小数点"),
]


def parse(student_no):
    """返回 dict；无法解析时返回 {'parsed': False} 且不带任何其它键。

    失败时绝不填默认值、不做任何兜底——展示错误的解析结果比展示
    「无法解析」危害更大，它会让管理员做出错误判断。
    """
    if student_no is None:
        return {"parsed": False}
    m = PATTERN.match(student_no)
    if not m:
        return {"parsed": False}
    year, cls, major, class_no, _serial = m.groups()
    return {
        "parsed": True,
        "entrance_year": 2000 + int(year),
        "major_class_code": cls,
        "major_code": cls + major,
        "class_no": class_no,
    }


def check():
    bad = 0
    print("=" * 60)
    print("学号解析规则校验")
    print("=" * 60)

    for raw, expected, note in FIXTURES:
        got = parse(raw)
        if got != expected:
            print("FAIL %s" % raw)
            print("  期望 %s" % expected)
            print("  实际 %s" % got)
            bad += 1
        else:
            print("ok   %s -> %d级 %s %s班  [%s]"
                  % (raw, got["entrance_year"], got["major_code"],
                     got["class_no"], note))

    for raw, why in NEGATIVE:
        got = parse(raw)
        if got.get("parsed") is not False:
            print("FAIL 应解析失败却成功了: %r (%s) -> %s" % (raw, why, got))
            bad += 1
        elif len(got) != 1:
            # 失败时只能有 parsed 一个键；多出来的键说明塞了默认值
            print("FAIL 解析失败时携带了额外字段: %r -> %s" % (raw, got))
            bad += 1
        else:
            print("ok   按预期拒绝 %r (%s)" % (raw, why))

    print("-" * 60)
    print("样本 %d 条，拒绝用例 %d 条，失败 %d 条"
          % (len(FIXTURES), len(NEGATIVE), bad))
    return bad


def self_test():
    """灵敏度自检：故意注入失败，证明本脚本真能抓到，而不是永远 PASS。

    关键：pattern 存在用例表里、在循环内才赋值给全局 PATTERN。
    如果在 append 之后立刻还原，等于注入从未发生，自检会永远显示
    「判据失效」或更糟——显示全部通过。三次注入必须真的跑到 check() 上。
    """
    print("=" * 60)
    print("灵敏度自检（注入已知失败）")
    print("=" * 60)

    cases = [
        ("班级位只允许 1 位",
         re.compile(r"^(\d{2})(\d{4})(\d{2})(\d{1,2})(\d{2,4})$"),
         "模拟后端把班级位误写成\\d{1,2}：14 位样本应因此解析失败"),
        ("流水号允许为空",
         re.compile(r"^(\d{2})(\d{4})(\d{2})(\d{2})(\d*)$"),
         "模拟后端漏掉长度下界：10 位输入应被拒绝却会被接受"),
        ("专业类与专业位错位",
         re.compile(r"^(\d{2})(\d{2})(\d{4})(\d{2})(\d{2,4})$"),
         "模拟位 3-6 / 7-8 切错：080901 会被切成 08|0901"),
        ("去掉长度上界",
         re.compile(r"^(\d{2})(\d{4})(\d{2})(\d{2})(\d*)$"),
         "模拟后端误加宽松尾部：15 位输入应被拒绝却会被接受"),
    ]

    global PATTERN
    origin = PATTERN
    detected = 0
    try:
        for name, bad_pattern, why in cases:
            PATTERN = bad_pattern
            before = sys.stdout
            sys.stdout = open(os.devnull, "w")
            try:
                bad = check()
            finally:
                sys.stdout.close()
                sys.stdout = before
            if bad > 0:
                print("PASS 注入「%s」后报出 %d 条失败——判据有效" % (name, bad))
                print("      %s" % why)
                detected += 1
            else:
                print("FAIL 注入「%s」后仍然全绿——判据失效" % name)
                print("      %s" % why)
    finally:
        PATTERN = origin

    print("-" * 60)
    print("注入 %d 项，捕获 %d 项" % (len(cases), detected))
    return 0 if detected == len(cases) else 1


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(1 if check() else 0)