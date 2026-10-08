# -*- coding: utf-8 -*-
"""脱敏实测：证明 UserVoAssembler 收口后,全站不再泄露username 原值。

对照三条路径:
  1. /user/me       —— 走 ofPrivate,应当脱敏
  2. /post/{id}     —— 走 PostVoAssembler,原先吐原值,这是泄露最严重的一条(Feed 与详情共用)
  3. /post/{id}/comments —— 走 CommentTreeAssembler,原先吐原值

判定: 响应里出现完整邮箱(不含 ***)即判失败。
"""
import sys, io, re
sys.stdout = io.TextIWrapper = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, ".")
import sm
from sm import req

BASE_OVERRIDE = "http://127.0.0.1:18080"


def req_at(method, path, body=None, token=None):
    """sm.req 的 18080 版本"""
    import json, urllib.request, urllib.error
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json;charset=utf-8"}
    if token:
        headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(BASE_OVERRIDE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))
    except Exception as e:
        return 0, {"error": str(e)}


EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# 手机号:11 位纯数字,前后各不能再接数字。
# 前后的 \D 用来挡住 138123456789 这类超长数字串里截出的假阳性,
# 同时也保证已脱敏的 138****5678 不被匹配(中间有 **** 不是 11 位连续数字)。
PHONE = re.compile(r"(?<!\d)1\d{10}(?!\d)")

FAIL = []


def _scan(o):
    """返回该字符串里疑似未脱敏的邮箱与手机号"""
    hits = []
    # 先剥掉已脱敏片段(形如 al***e@x.com / 138****5678),剩下的才判
    residue = re.sub(r"[A-Za-z0-9._%+-]*\*\*\*[A-Za-z0-9._%+-]*@[A-Za-z0-9.-]+", "", o)
    residue = re.sub(r"1\d{2}\*{4}\d{4}", "", residue)
    for m in EMAIL.findall(residue):
        hits.append(("邮箱", m))
    for m in PHONE.findall(residue):
        hits.append(("手机号", m))
    return hits


def check(label, obj):
    """递归扫响应里所有字符串,找出未被脱敏的邮箱/手机号。

    ★ 第一版判据是错的,两个错:
    (1) 用findall 逐个匹配邮箱,而al***e@campus.test里的 *** 恰好把 local
        部分打断,正则从 'e' 重新起步匹配到 'e@campus.test',于是一个脱敏
        得很好的字符串被报成 FAIL——三条路径全红,而服务端返回的是正确值。
        假阴性比假阳性更浪费,它会让人去改本来正确的代码。
    (2) 只查邮箱形态,完全没查手机号。灵敏度验证时13812345678 被直接放过,
        而 username 字段本来就同时接受邮箱与手机号,注册走手机号的用户
        会从这条缝里完整泄露。
    正确判据:先剥掉含 *** 的片段,剩下的里若还有邮箱或 11 位手机号才算泄露。
    """
    hits = []

    def walk(o, path="$"):
        if isinstance(o, str):
            for kind, val in _scan(o):
                hits.append((path, kind, val, o))
        elif isinstance(o, dict):
            for k, v in o.items():
                walk(v, path + "." + k)
        elif isinstance(o, list):
            for i, v in enumerate(o):
                walk(v, path + "[%d]" % i)

    walk(obj)
    if hits:
        FAIL.append((label, hits))
        print("  [FAIL] %s 发现未脱敏%s" % (label, hits))
    else:
        print("  [ ok ] %s 无未脱敏邮箱/手机号" % label)


print("\n=== 0. 判据自检（灵敏度验证）===")
# 为什么必须每次都跑:判据本身写错时报的是 FAIL,而修复方会照着去改本来正确的代码。
# 没有自检的检查等于把「有没有问题」交给了运气。先证明它能抓到真泄露。
SELF_CASES = [
    ("脱敏正确的邮箱", "al***e@campus.test", False),
    ("未脱敏的邮箱", "alice@campus.test", True),
    ("脱敏正确的手机号", "138****5678", False),
    ("未脱明的手机号", "13812345678", True),
    ("邮箱混在句子里", "联系方式 bob@campus.test 请发", True),
    ("超长数字串不该误报", "订单号 1381234567890", False),
]
SELF_FAILED = []
for label, val, should_hit in SELF_CASES:
    got = bool(_scan(val))
    ok = got == should_hit
    if not ok:
        SELF_FAILED.append(label)
    print("  %s %-22s 期望%s 实际%s" % ("[ok]  " if ok else "[FAIL]", label,
                                       "抓到" if should_hit else "放过",
                                       "抓到" if got else "放过"))
if SELF_FAILED:
    print("  ★ 判据本身不可信,以下用例判错:", SELF_FAILED)
    FAIL.append(("判据自检失败", SELF_FAILED))
else:
    print("  判据在6 个用例上均符合预期 ✅")

print("\n=== 1. 登录取 token ===")
s, b = req_at("POST", "/auth/login", body={"username": "alice@campus.test", "password": "user123"})
tok = (b.get("data") or {}).get("accessToken") if s == 200 else None
if not tok:
    print("登录失败:", s, b)
    sys.exit(1)
print("  token 已取得")

print("\n=== 2. GET /user/me （ofPrivate 路径）===")
s, b = req_at("GET", "/user/me", token=tok)
print("  HTTP", s)
if s == 200:
    u = (b.get("data") or {})
    print("  username 实际返回:", repr(u.get("username")))
    check("/user/me", u)
else:
    FAIL.append(("/user/me 不可达", s))
    print("  [FAIL] HTTP", s, b)

print("\n=== 3. GET /post/72 （PostVoAssembler 路径,原先泄露）===")
s, b = req_at("GET", "/post/72", token=tok)
print("  HTTP", s)
if s == 200:
    d = b.get("data") or {}
    author = d.get("author") or {}
    print("  author.username 实际返回:", repr(author.get("username")))
    check("/post/72 author", author)
else:
    print("  [跳过] 帖子不存在或不可达, HTTP", s)
    FAIL.append(("/post/72", s))

print("\n=== 4. GET /post/72/comments （CommentTreeAssembler 路径,原先泄露）===")
s, b = req_at("GET", "/post/72/comments", token=tok)
print("  HTTP", s)
if s == 200:
    lst = (b.get("data") or {}).get("list") or []
    print("  一级评论数:", len(lst))
    if lst:
        print("  第一条的 user.username:", repr((lst[0].get("user") or {}).get("username")))
    check("/post/72/comments", lst)
else:
    print("  [跳过] HTTP", s)
    FAIL.append(("/post/72/comments", s))

print("\n=== 结论 ===")
if FAIL:
    print("失败 %d 项:" % len(FAIL))
    for label, detail in FAIL:
        print("  -", label, detail)
    sys.exit(1)
print("三条路径均无未脱敏邮箱 ✅")
