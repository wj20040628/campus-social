# -*- coding: utf-8 -*-
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, ".")
from sm import req

def login(u, p):
    s, b = req("POST", "/auth/login", body={"username": u, "password": p})
    return (b.get("data") or {}).get("accessToken") if s == 200 and isinstance(b, dict) and b.get("code") == 200 else None

alice = login("alice@campus.test", "user123")
bob = login("bob@campus.test", "user123")
assert alice and bob

s, b = req("GET", "/post/feed?size=5", token=alice)
lst = (b.get("data") or {}).get("list") or []
print("feed", s, "posts", [p["id"] for p in lst])
if not lst:
    print("no posts, abort"); sys.exit(0)
pid = lst[0]["id"]
print("target post", pid)

s, b = req("GET", "/post/%d/comments?size=10" % pid, token=alice)
print("comments", s)
d = b.get("data") or {}
roots = d.get("list") or []
print("roots:", [(c["id"], c["level"], len(c.get("children") or []), c.get("childrenOverflowCount")) for c in roots])
if roots:
    r0 = roots[0]
    print("root0 keys sample:", {k: r0.get(k) for k in ("id","level","parentId","likeCount","liked","childrenOverflowCount")})
    ch = r0.get("children") or []
    if ch:
        print("child0:", {k: ch[0].get(k) for k in ("id","level","parentId","replyToNickname","childrenOverflowCount")})
        if ch[0].get("children"):
            print("grandchild0:", {k: ch[0]["children"][0].get(k) for k in ("id","level","parentId")})
    # 溢出端点
    s2, b2 = req("GET", "/post/%d/comment/%d/children?size=5" % (pid, r0["id"]), token=alice)
    print("children endpoint", s2, str(b2)[:220])
    s3, b3 = req("GET", "/post/%d/comment/999999/children" % pid, token=alice)
    print("children of missing", s3, b3)
