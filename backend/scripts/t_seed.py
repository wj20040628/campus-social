# -*- coding: utf-8 -*-
import sys, io, json
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.path.insert(0, ".")
from sm import req

def login(u, p):
    s, b = req("POST", "/auth/login", body={"username": u, "password": p})
    return (b.get("data") or {}).get("accessToken") if s == 200 and isinstance(b, dict) and b.get("code") == 200 else None

alice = login("alice@campus.test", "user123")
bob = login("bob@campus.test", "user123")
carol = login("carol@campus.test", "user123")
assert alice and bob and carol
pid = 72

# 一级：alice
s, b = req("POST", "/post/%d/comment" % pid, body={"content": "评论树冒烟：L1 一级评论", "level": 1}, token=alice)
print("L1", s, b)
l1 = b["data"]["commentId"]

# 二级：bob 回复 alice（parentId = L1）
s, b = req("POST", "/post/%d/comment" % pid, body={"content": "评论树冒烟：L2 二级回复", "level": 2, "parentId": l1, "replyToUserId": 2}, token=bob)
print("L2", s, b)
l2 = b["data"]["commentId"]

# 三级：carol 回复 bob（按契约 parentId 填一级祖先 L1）
s, b = req("POST", "/post/%d/comment" % pid, body={"content": "评论树冒烟：L3 三级回复", "level": 3, "parentId": l1, "replyToUserId": 3}, token=carol)
print("L3", s, b)
l3 = b["data"]["commentId"]

# 第二条二级
s, b = req("POST", "/post/%d/comment" % pid, body={"content": "评论树冒烟：L2-2", "level": 2, "parentId": l1, "replyToUserId": 2}, token=carol)
print("L2-2", s, b)

print("IDS", l1, l2, l3)
