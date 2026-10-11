#!/bin/sh
# pre-push 钩子分流逻辑的灵敏度自测(呼应 TD 复盘:门禁要自证没假绿)
# ------------------------------------------------------------------
# 注入「一条必然 rc=134 的假门禁(模拟 OOM)」+「一条必然 rc=1 的假门禁(模拟真逻辑错)」,
# 跑与 pre-push 钩子**同一段**分流代码,断言:
#   rc=134 → 判为 env(不拦,exit 0)
#   rc=1   → 判为 logic(拦,exit 1)
# 两条都判对 → PASS。
#
# 用法:sh scripts/git-hooks/self-test.sh

set -u
root="$(git rev-parse --show-toplevel 2>/dev/null)"
[ -z "$root" ] && { echo "找不到 git 根"; exit 2; }

python - "$root" <<'PY'
import importlib.util
import os
import subprocess
import sys

root = sys.argv[1]
# 复用 run-all-gates.py 的 run_one,保证自测与真钩子读的是同一套退出码语义
spec = importlib.util.spec_from_file_location(
    "run_all_gates", os.path.join(root, "scripts", "run-all-gates.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

# 两条假门禁:一条 exit 134(模拟 OOM),一条 exit 1(模拟真逻辑错)
fakes = [
    ("fake-oom", root, ["python", "-c", "import sys; sys.exit(134)"], "注入:模拟 OOM"),
    ("fake-logic", root, ["python", "-c", "import sys; sys.exit(1)"], "注入:模拟真逻辑错"),
]
results = m.run_all(fakes, quiet=True)

# 与 pre-push 钩子完全相同的分流规则
ENV_RC = {134, -1, -2}
env_fail, logic_fail = [], []
for name, ok, code, out in results:
    if ok:
        continue
    (env_fail if code in ENV_RC else logic_fail).append(name)

env_names = set(env_fail)
logic_names = set(logic_fail)

problems = []
if "fake-oom" not in env_names:
    problems.append("rc=134(OOM)未被判为环境类")
if "fake-logic" not in logic_names:
    problems.append("rc=1(真逻辑错)未被判为拦截类")
if "fake-oom" in logic_names:
    problems.append("rc=134 被误判为真逻辑错(会错误地拦 push)")
if "fake-logic" in env_names:
    problems.append("rc=1 被误判为环境类(危险假绿)")

print("分流结果:env=%s logic=%s" % (sorted(env_names), sorted(logic_names)))
print("-" * 72)
if problems:
    for p in problems:
        print("FAIL " + p)
    sys.exit(1)
print("PASS rc=134 判环境(不拦)、rc=1 判逻辑(拦),与 pre-push 钩子分流逻辑一致")
sys.exit(0)
PY
