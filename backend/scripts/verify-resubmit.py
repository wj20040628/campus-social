#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""驳回后改学号能否重新进待审队列 —— 用真 jar + 真 MySQL 3306 验证。

不用 mysql -e 多语句回读：那个只返回最后一句输出，当判据会假绿
（后端上一轮就是这么假绿的）。本脚本每条断言都用**单条**查询。
"""
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18080"
MYSQL = r"F:\MySQL\MySQL Server 9.0\bin\mysql.exe"
REDIS = r"D:/Redis-x64-5.0.14.1/redis-cli.exe"


def api(path, method="GET", body=None, tok=None):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if tok:
        headers["Authorization"] = "Bearer " + tok
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        r = urllib.request.urlopen(req)
        return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw}


def q(where_col, val):
    """单条查询取一行，拆成 dict。刻意不用多语句。"""
    sql = ("SELECT CONCAT(id,'|',student_no,'|',verify_status) FROM user "
           "WHERE %s='%s' LIMIT 1" % (where_col, val))
    out = subprocess.run(
        [MYSQL, "--default-character-set=utf8mb4", "-uroot", "-p062828",
         "-P3306", "campus_social_v2", "-N", "-B", "-e", sql],
        capture_output=True, text=True).stdout.strip()
    if not out:
        return None
    i, sno, vs = out.split("|")
    return {"id": int(i), "studentNo": sno, "verifyStatus": int(vs)}


def token_for(email):
    """给指定邮箱拿 token。

    ★必须用「被测用户自己的 token」调 PUT /user/me——
    这个端点改的是**当前登录的人**。用管理员 token 去改被测用户，
    改的其实是管理员自己，断言再仔细也是错的
    （我第一版就这么错的：A 条after 查到 1，因为我把学号写进了管理员行）。
    """
    for attempt in range(6):
        for k in ("code:limit:%s" % email,
                  "code:daily:login:%s" % email):
            subprocess.run([REDIS, "DEL", k], capture_output=True)
        api("/auth/send-code", "POST", {"target": email, "type": "login"})
        time.sleep(1)
        code = subprocess.run([REDIS, "GET", "code:login:%s" % email],
                              capture_output=True, text=True).stdout.strip()
        if not code:
            time.sleep(5)
            continue
        st, d = api("/auth/login-code", "POST",
                    {"email": email, "code": code})
        if st == 200:
            return d["data"]["accessToken"]
        time.sleep(5)
    print("登录失败：%s" % email)
    sys.exit(1)


def admin_token():
    return token_for("admin@campus.test")


def main():
    print("=" * 64)
    print("驳回后改学号 —— 真 jar + 真 MySQL 验证")
    print("=" * 64)
    adm = admin_token()
    print("管理员登录成功\n")

    bad = 0

    def check(name, ok, detail=""):
        nonlocal bad
        if not ok:
            bad += 1
        print("  [%s] %s%s" % ("PASS" if ok else "FAIL", name,
                               ("  " + detail) if detail else ""))

    # ---- A: 状态 3 改新学号 -> 应变2 并进待审队列
    print("=== A: 已驳回(3) 改新学号 ===")
    tok_a = token_for("vfy_a@c.test")
    before = q("username", "vfy_a@c.test")
    print("  before: verify_status=%s student_no=%s"
          % (before["verifyStatus"], before["studentNo"]))
    st, d = api("/user/me", "PUT", {"studentNo": "24985000001"}, tok_a)
    print("  PUT /user/me -> HTTP %s %s" % (st, d.get("message", "")))
    after = q("username", "vfy_a@c.test")
    print("  after : verify_status=%s student_no=%s"
          % (after["verifyStatus"], after["studentNo"]))
    check("A状态 3 -> 2", after["verifyStatus"] == 2,
          "实际=%s" % after["verifyStatus"])
    check("A 学号已更新", after["studentNo"] == "24985000001",
          "实际=%s" % after["studentNo"])
    st, d = api("/admin/verify/pending", tok=adm)
    ids = [i["userId"] for i in d["data"]["list"]]
    check("A 出现在待审队列", before["id"] in ids, "队列=%s" % ids)
    print()

    # ---- B: 改已被占用的学号 -> 应 409 且状态不变
    print("=== B: 已驳回(3) 改【已被占用】的学号 ===")
    tok_b = token_for("vfy_a@c.test")
    st, d = api("/user/me", "PUT", {"studentNo": "2408091003803"}, tok_b)
    print("  PUT /user/me -> HTTP %s %s" % (st, d.get("message", "")))
    check("B 返回 409", st == 409, "实际=%s" % st)
    b_after = q("username", "vfy_a@c.test")
    check("B 状态未被改动（仍 2）", b_after["verifyStatus"] == 2,
          "实际=%s" % b_after["verifyStatus"])
    print()

    # ---- C: 已认证(1) 改资料 -> 必须仍是 1（本轮最重要的边界）
    print("=== C: 已认证(1) 改学号 —— 最关键的边界 ===")
    tok_c = token_for("vfy_c@c.test")
    before = q("username", "vfy_c@c.test")
    print("  before: verify_status=%s" % before["verifyStatus"])
    st, d = api("/user/me", "PUT", {"studentNo": "24985000002"}, tok_c)
    print("  PUT 改学号 -> HTTP %s" % st)
    c1 = q("username", "vfy_c@c.test")
    print("  after : verify_status=%s" % c1["verifyStatus"])
    check("C 仍为 1（未被拉回审核）", c1["verifyStatus"] == 1,
          "实际=%s" % c1["verifyStatus"])
    st, d = api("/user/me", "PUT", {"nickname": "C改了昵称"}, tok_c)
    c2 = q("username", "vfy_c@c.test")
    print("  PUT 改昵称 -> HTTP %s, verify_status=%s" % (st, c2["verifyStatus"]))
    check("C 改昵称后仍为 1", c2["verifyStatus"] == 1)
    st, d = api("/admin/verify/pending", tok=adm)
    ids = [i["userId"] for i in d["data"]["list"]]
    check("C 不在待审队列", before["id"] not in ids, "队列=%s" % ids)

    print("=" * 64)
    print("结果：%s（失败 %d 条）" % ("全部通过" if bad == 0 else "有失败", bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())