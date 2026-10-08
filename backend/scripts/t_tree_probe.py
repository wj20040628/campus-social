# -*- coding: utf-8 -*-
"""真机验评论树:发L1/L2/L3 三级评论,再看接口是否真的返回嵌套 children。
这是本次交付里最容易「编译通过、单测通过、线上恒空」的一处,必须真打一次。"""
import json, urllib.request, urllib.error
BASE="http://127.0.0.1:18080"
def req(m,p,b=None,t=None):
    d=json.dumps(b,ensure_ascii=False).encode() if b is not None else None
    h={"Content-Type":"application/json;charset=utf-8"}
    if t: h["Authorization"]="Bearer "+t
    r=urllib.request.Request(BASE+p,data=d,headers=h,method=m)
    try:
        with urllib.request.urlopen(r,timeout=10) as x: return x.status, json.loads(x.read().decode())
    except urllib.error.HTTPError as e:
        try: return e.code, json.loads(e.read().decode())
        except: return e.code, {}

def login(u,p="user123"):
    s,b=req("POST","/auth/login",{"username":u,"password":p})
    return (b.get("data") or {}).get("accessToken") if s==200 else None

alice=login("alice@campus.test"); bob=login("bob@campus.test"); carol=login("erin@campus.test")
print("登录: alice=%s bob=%s carol=%s" % (bool(alice),bool(bob),bool(carol)))
if not(alice and bob and carol): raise SystemExit("登录失败")

PID=72
tag="PROBE"
s,b=req("POST","/post/%d/comment"%PID,{"content":tag+" L1","level":1},alice)
print("发L1:",s,b.get("code") if isinstance(b,dict) else b)
l1=b["data"]["commentId"]
s,b=req("POST","/post/%d/comment"%PID,{"content":tag+" L2","level":2,"parentId":l1,"replyToUserId":2},bob)
print("发L2:",s,b.get("code") if isinstance(b,dict) else b)
l2=b["data"]["commentId"]
s,b=req("POST","/post/%d/comment"%PID,{"content":tag+" L3","level":3,"parentId":l1,"replyToUserId":6},carol)
print("发L3:",s,b.get("code") if isinstance(b,dict) else b)

print("\n=== 拉评论列表,看树 ===")
s,b=req("GET","/post/%d/comments?size=50"%PID,None,alice)
lst=(b.get("data") or {}).get("list") or []
mine=[c for c in lst if tag in (c.get("content") or "")]
print("命中本次探针的一级评论 %d 条" % len(mine))
for c in mine:
    ch=c.get("children") or []
    print("  L1 id=%s level=%s children=%d overflow=%s" % (
        c.get("id"), c.get("level"), len(ch), c.get("childrenOverflowCount")))
    for c2 in ch:
        ch2=c2.get("children") or []
        print("    L2 id=%s level=%s parentId=%s children=%d" % (
            c2.get("id"), c2.get("level"), c2.get("parentId"), len(ch2)))
        for c3 in ch2:
            print("      L3 id=%s level=%s parentId=%s" % (c3.get("id"),c3.get("level"),c3.get("parentId")))
    if ch and any((c2.get("children") or []) for c2 in ch):
        print("  >>> 三级确实挂上了")
    else:
        print("  >>> !! 三级丢失")
