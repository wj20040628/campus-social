"""openapi.yaml 完整性校验。

三件事，都必须真跑而不是目测：
  1. YAML 能被解析
  2. 所有 $ref 都能在 components/{schemas,parameters,responses} 里找到定义
  3. 路径 / 方法 / schema 的真实计数（报出来，不靠人记）

★ 为什么必须有第2 条：我曾引用过一个不存在的
  components/schemas/ResultOfObject，写的时候没发现，
  是后端 teammate 从编译错误里撞出来的。悬空 $ref 不会让 YAML 校验失败，
  只会在生成客户端或拼响应时炸。
"""
import re
import sys

try:
    import yaml
except ImportError:
    print("需要 pyyaml：pip install pyyaml")
    sys.exit(2)

PATH = "docs/api/openapi.yaml"

with open(PATH, encoding="utf-8") as f:
    doc = yaml.safe_load(f)

paths = doc.get("paths") or {}
schemas = (doc.get("components") or {}).get("schemas") or {}
responses = (doc.get("components") or {}).get("responses") or {}
parameters = (doc.get("components") or {}).get("parameters") or {}

METHODS = {"get", "post", "put", "delete", "patch", "head", "options"}

n_paths = len(paths)
n_methods = sum(1 for p, item in paths.items() for m in item if m.lower() in METHODS)
n_schemas = len(schemas)

# ── $ref 悬空检查 ──────────────────────────────────────
raw = open(PATH, encoding="utf-8").read()
refs = re.findall(r"\$ref:\s*['\"]?#/components/(\w+)/([A-Za-z0-9_.\-]+)", raw)

NAMESPACES = {"schemas": schemas, "responses": responses, "parameters": parameters}

dangling = []
for ns, name in refs:
    if ns not in NAMESPACES:
        dangling.append(("%s/%s" % (ns, name), "命名空间 %s 不存在" % ns))
    elif name not in NAMESPACES[ns]:
        dangling.append(("%s/%s" % (ns, name), "定义缺失"))

print("=" * 60)
print("openapi.yaml 完整性校验")
print("=" * 60)
print("路径数        : %d" % n_paths)
print("方法数        : %d" % n_methods)
print("schema 数     : %d" % n_schemas)
print("$ref 总数     : %d" % len(refs))
print("悬空 $ref     : %d" % len(dangling))
for name, why in dangling:
    print("   ★ %s —— %s" % (name, why))

# ── 自检：把计数与文件自述的口径对齐 ───────────────────
auth = [p for p in paths if p.startswith("/auth/")]
print("auth 端点数   : %d %s" % (len(auth), sorted(auth) if auth else ""))

if n_paths == 0 or n_schemas == 0:
    print("★ 自检失败：路径或 schema 数为 0，解析本身就不对")
    sys.exit(2)

sys.exit(1 if dangling else 0)
