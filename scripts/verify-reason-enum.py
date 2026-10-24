#!/usr/bin/env python3
"""
举报理由枚举一致性门禁（2026-10-24 加）。

【为什么需要这道门禁】
`reasonType` 是一个码（1–5），文案由前端渲染。这份「码 → 文案」的映射
原先在**两处**各写一遍：
  - frontend/src/components/post/ReportSheet.vue   （用户看到的选项）
  - frontend/src/composables/useReportQueue.js     （管理员看到的文案）
两份漂移过：管理端写成「2不实信息 3广告营销 4违规内容」，
而契约与用户端都是「2广告 3色情 4诈骗」。
于是用户举报「色情」（码 3），管理员屏幕上显示「广告营销」——
管理员据此决定处置方向，理由标签错了，依据就错了。

这类 bug 的可怕之处在于**两边各自都自洽**：单独打开任一端都看不出问题，
只有把两端摆在一起对照才会暴露。这正适合交给机器，不该靠人记得对一遍。

【现在守什么】
文案唯一出处在 frontend/src/constants/reportReason.js。
本门禁校验它与契约（docs/api/openapi.yaml 的 reasonType.description）
逐项一致 —— 契约是那份「1骚扰 2广告 3色情 4诈骗 5其他」的唯一权威表述。

契约将来加了新码，这里会失败并要求同步，而不是让新码在前端显示成空白。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

CONST_FILE = os.path.join(PROJECT, "frontend", "src", "constants", "reportReason.js")
OPENAPI = os.path.join(PROJECT, "docs", "api", "openapi.yaml")

failures = []


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


# ---------- 1. 从契约取权威映射 ----------
openapi_text = read(OPENAPI)
# 形如：description: 1骚扰 2广告 3色情 4诈骗 5其他
m = re.search(r"reasonType:\s*\n\s*type: integer\s*\n\s*description:\s*(\d[^\n]*)", openapi_text)
if not m:
    failures.append(
        "在 docs/api/openapi.yaml 里找不到 reasonType 的 description（形如「1骚扰 2广告 3色情 4诈骗 5其他」）"
    )
    contract = {}
else:
    desc = m.group(1).strip()
    # 逐个抓「数字 + 紧跟的中文」，允许中间无空格
    contract = {int(code): label for code, label in re.findall(r"(\d+)([\u4e00-\u9fa5]+)", desc)}
    if not contract:
        failures.append(f"未能从契约描述里解析出映射：{desc!r}")

# ---------- 2. 从前端常量取实际映射 ----------
const_text = read(CONST_FILE)
pairs = re.findall(r"\{\s*v:\s*(\d+)\s*,\s*label:\s*'([^']+)'\s*\}", const_text)
actual = {int(v): label for v, label in pairs}
if not actual:
    failures.append(
        f"未能从 {os.path.relpath(CONST_FILE, PROJECT)} 的 REPORT_REASONS 里解析出 {{v, label}} 项"
    )

# ---------- 3. 逐项比对 ----------
if contract and actual:
    only_contract = sorted(set(contract) - set(actual))
    only_actual = sorted(set(actual) - set(contract))
    if only_contract:
        failures.append(f"契约有但前端缺的码：{only_contract}")
    if only_actual:
        failures.append(f"前端有但契约没定义的码：{only_actual}")
    for code in sorted(set(contract) & set(actual)):
        if contract[code] != actual[code]:
            failures.append(
                f"码 {code} 文案不一致：契约「{contract[code]}」 vs 前端「{actual[code]}」"
            )

# ---------- 4. 反向守住「只有一处定义」 ----------
# 文案字面量不该散落在组件里。允许白名单：常量文件自身、法务文案。
allowed = {
    os.path.join("frontend", "src", "constants", "reportReason.js"),
    os.path.join("frontend", "src", "constants", "legalDocs.js"),
}
scan_root = os.path.join(PROJECT, "frontend", "src")
for dirpath, _dirnames, filenames in os.walk(scan_root):
    for name in filenames:
        if not name.endswith((".vue", ".js")):
            continue
        full = os.path.join(dirpath, name)
        rel = os.path.relpath(full, PROJECT)
        if rel in allowed:
            continue
        text = read(full)
        for code, label in sorted(contract.items()):
            # 只在「与多个其它理由并列出现」时才算重复定义，
            # 避免把正文里偶尔提到「色情」的注释误报
            hits = len(re.findall(rf"['\"]{re.escape(label)}['\"]", text))
            others = sum(
                1 for c2, l2 in contract.items() if c2 != code and re.search(rf"['\"]{re.escape(l2)}['\"]", text)
            )
            if hits and others >= 2:
                failures.append(
                    f"{rel} 里又写了一份举报理由文案（发现「{label}」等 ≥3 类）—— "
                    f"应改为从 constants/reportReason.js 导入"
                )

if failures:
    print("举报理由枚举不一致：")
    for f in failures:
        print("  -", f)
    sys.exit(1)

print(f"举报理由枚举一致（{len(contract)} 项），且只有一处定义")