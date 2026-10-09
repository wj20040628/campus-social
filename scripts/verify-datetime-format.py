"""契约日期格式完整性检查。

存在的理由：后端曾报advisory「topExpireTime 缺 format: date-time，
管理端传空格分隔会 500」。修完后需要一个可复现的门禁防回退。

★ 上一版这个检查是假绿：正则写成 ^\\s+topExpireTime:\\s*$ 并在 shell
  双引号里传给 python，反斜杠被 shell 吃掉变成字面 $，匹配 0 处，
  却按「没匹配到 = 没缺格式」输出「无 ✅」。0 次匹配和 0 处缺陷
  在代码里长得一模一样。所以下面显式打印匹配次数，次数为 0 直接判失败。
"""
import re
import sys

CONTRACT = "docs/api/openapi.yaml"

# 契约里所有「后端用 LocalDateTime 接收」的字段。
# 每个都必须带 format: date-time，否则前端传 "2026-10-15 16:27:40"
# 这种空格分隔形式会在 Jackson 反序列化时抛 DateTimeParseException → 500。
DATETIME_FIELDS = [
    "topExpireTime",
    "createTime",
    "updateTime",
    "readTime",
    "expireTime",
    "startTime",
    "endTime",
    # 学号核验待审列表的提交时间。后端 LocalDateTime 接收，
    # 缺 format 会让前端传 "2026-10-15 16:27:40" 直接 500
    "submittedAt",
]

# 刻意豁免:字段名像时间戳、但契约里定义为 integer/int64 的 epoch 毫秒,
# 不带 date-time 是**对的**,不是缺。把它们从 DATETIME_FIELDS 里拿出来单独列,
# 免得下一轮看到 accessExpireAt 又当成「该带 date-time 的字段」加回上面,再误报一轮。
# 依据:openapi.yaml:3537 accessExpireAt `type: integer, format: int64`,由 JwtUtil 算出。
INT64_EPOCH_MS_FIELDS = [
    "accessExpireAt",
    "refreshExpireAt",
]

lines = open(CONTRACT, encoding="utf-8").read().split("\n")

total_checked = 0
missing = []
never_seen = []
int64_ok = []

for field in DATETIME_FIELDS:
    pat = re.compile(r"^\s+" + re.escape(field) + r":\s*$")
    hits = [i for i, l in enumerate(lines) if pat.match(l)]
    if not hits:
        never_seen.append(field)
        continue
    for i in hits:
        total_checked += 1
        block = "\n".join(lines[i : i + 6])
        if "format: date-time" not in block:
            missing.append((field, i + 1))

# int64 epoch-ms 字段:只验证「存在且确为 integer」,绝不断言 date-time。
for field in INT64_EPOCH_MS_FIELDS:
    pat = re.compile(r"^\s+" + re.escape(field) + r":\s*$")
    hits = [i for i, l in enumerate(lines) if pat.match(l)]
    for i in hits:
        block = "\n".join(lines[i : i + 6])
        if "type: integer" in block and "format: int64" in block:
            int64_ok.append(field)
        else:
            # 定义变了(不再是 int64),才需要重新进 DATETIME_FIELDS 的讨论
            missing.append((field + " (期望 integer/int64, 现形不匹配)", i + 1))

print("契约文件:", CONTRACT)
print("已检查字段实例数:", total_checked)
print("在契约里找到的字段:", [f for f in DATETIME_FIELDS if f not in never_seen])
print("契约里未出现的字段(可能已改名，不视为缺陷):", never_seen)
print("int64 epoch-ms 豁免字段(确认无 date-time 属正常):", int64_ok)
print("缺 format: date-time 的位置:", missing if missing else "无")

# 自检：一次都没匹配到，说明正则或路径有问题，不能输出「无」
if total_checked == 0:
    print("★ 自检失败：一个字段都没匹配到，检查本身无效（正则错或路径错）")
    sys.exit(2)

sys.exit(1 if missing else 0)
