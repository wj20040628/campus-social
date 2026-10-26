# -*- coding: utf-8 -*-
"""上线前清理测试账号及其牵连数据。默认 dry-run，不写库。

为什么不能只写一句 DELETE FROM user WHERE id NOT IN (1,2)：
  本库 39 张表、外键约束数为 0，数据库不会替你级联。只删 user 行会在
  post / feed_item / like / notify / group_* / chat_* 等表里留下指向
  不存在用户的孤儿行，表现为计数虚高、列表出现空作者、点详情 500。

计数重算不在这里做：删完后启动一次后端（或等凌晨 3 点），ReconcileJob
  会按 UserMapper / PostMapper / CircleMapper 里的权威 SQL 重算
  user / post / circle 计数。脚本里再写一份 UPDATE 就是第二个真相源。

用法：
  python clean_test_data.py --keep 1,2                       # 只看清单
  python clean_test_data.py --keep 1,2 --backup D:\\bk.sql --apply
"""
import argparse
import os
import re
import sys

import pymysql

# 存 user.id 的列（孤儿审计用）。刻意不含 target_id（like/report 的多态目标）、
# dept_id / major_id / tag_id / grade_id / post_id / group_id 等非用户列。
USER_COLS = {
    "post": ["author_id"], "comment": ["user_id"], "like": ["user_id"],
    "post_favorite": ["user_id"], "notify": ["user_id", "actor_id"],
    "relation": ["from_user_id", "to_user_id"], "feed_item": ["fan_id", "author_id"],
    "circle": ["owner_id"], "circle_member": ["user_id"], "group": ["owner_id"],
    "group_member": ["user_id"], "group_message": ["sender_id"],
    "group_read": ["user_id"], "chat_conversation": ["member_a", "member_b"],
    "chat_message": ["sender_id", "receiver_id"], "chat_conv_pref": ["user_id"],
    "user_tag": ["user_id"], "mkt_activity": ["user_id"], "mkt_comment": ["user_id"],
    "activity": ["creator_id"], "activity_signup": ["user_id"],
    "buddy_post": ["user_id"], "buddy_join": ["user_id"],
    "announcement_read": ["user_id"], "feedback": ["user_id"],
    "report": ["reporter_id", "handler_id"], "review_task": ["reviewer_id"],
    "upload_file": ["user_id"], "audit_log": ["user_id"],
}


def connect():
    """连接优先取环境变量，回落 application-local.yml（该文件已 gitignore）。"""
    if os.getenv("MYSQL_HOST"):
        return pymysql.connect(
            host=os.environ["MYSQL_HOST"], port=int(os.getenv("MYSQL_PORT", "3306")),
            user=os.environ["MYSQL_USER"], password=os.environ["MYSQL_PASSWORD"],
            database=os.environ["MYSQL_DB"], charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor)
    path = os.path.join(os.path.dirname(__file__), os.pardir,
                        "src", "main", "resources", "application-local.yml")
    y = open(path, encoding="utf-8").read()
    m = re.search(r"url:\s*jdbc:mysql://([^:/]+):(\d+)/([^?\s]+)", y)
    u = re.search(r"^\s*username:\s*(\S+)", y, re.M)
    p = re.search(r"^\s*password:\s*(\S+)", y, re.M)
    return pymysql.connect(host=m.group(1), port=int(m.group(2)), user=u.group(1),
                           password=p.group(1), database=m.group(3), charset="utf8mb4",
                           cursorclass=pymysql.cursors.DictCursor)


def lit(seq):
    """把 id 列表转成 SQL 字面量；空列表用 0 占位，保证 IN (...) 语法合法。"""
    return ",".join(str(int(x)) for x in seq) if seq else "0"


def scalar(cur, sql):
    cur.execute(sql)
    return list(cur.fetchone().values())[0]


def col_ids(cur, sql):
    cur.execute(sql)
    return [list(r.values())[0] for r in cur.fetchall()]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", required=True, help="保留的用户 id，逗号分隔，如 1,2")
    ap.add_argument("--apply", action="store_true", help="真正执行删除（默认只打印）")
    ap.add_argument("--backup", help="--apply 时必填：已存在的 mysqldump 备份文件路径")
    ap.add_argument("--wipe-audit", action="store_true",
                    help="连 audit_log 一起删；默认保留安全审计留痕")
    ap.add_argument("--purge-deleted", action="store_true",
                    help="物理删除全库 is_deleted=1 的行（@TableLogic 软删残留，"
                         "应用层看不见但会随 mysqldump 一起进服务器）")
    a = ap.parse_args()

    keep = sorted({int(x) for x in a.keep.split(",") if x.strip()})
    if not keep:
        sys.exit("--keep 不能为空")

    c = connect()
    cur = c.cursor()
    db = c.db.decode() if isinstance(c.db, bytes) else (c.db or "")

    cur.execute("SELECT id,username,nickname,role FROM user ORDER BY id")
    users = cur.fetchall()
    doomed = [u for u in users if u["id"] not in keep]
    kept = [u for u in users if u["id"] in keep]

    print("保留账号（%d 个）：" % len(kept))
    for u in kept:
        print("  id=%-8s role=%s %-24s %s" % (u["id"], u["role"], u["username"], u["nickname"]))
    print("待删账号（%d 个）：" % len(doomed))
    for u in doomed:
        print("  id=%-8s role=%s %-24s %s" % (u["id"], u["role"], u["username"], u["nickname"]))

    # 安全闸：别把唯一的管理员删干净，否则后台无人能进。
    if not any(u["role"] == 1 for u in kept):
        c.close()
        sys.exit("拒绝执行：--keep 里没有 role=1 的管理员，删完后台将无人可进。")

    D = lit([u["id"] for u in doomed])

    # 派生 id 全部物化成字面量：删除语句之间不再互相依赖，
    # 不会出现「子查询引用的表已被删空」导致漏删。
    P = lit(col_ids(cur, "SELECT id FROM post WHERE author_id IN (%s)" % D))
    CM = lit(col_ids(cur, "SELECT id FROM comment WHERE post_id IN (%s)" % P))
    M = lit(col_ids(cur, "SELECT id FROM mkt_activity WHERE user_id IN (%s)" % D))
    MC = lit(col_ids(cur, "SELECT id FROM mkt_comment WHERE activity_id IN (%s)" % M))
    G = lit(col_ids(cur, "SELECT id FROM `group` WHERE owner_id IN (%s)" % D))
    CI = lit(col_ids(cur, "SELECT id FROM circle WHERE owner_id IN (%s)" % D))
    B = lit(col_ids(cur, "SELECT id FROM buddy_post WHERE user_id IN (%s)" % D))
    ACT = lit(col_ids(cur, "SELECT id FROM activity WHERE creator_id IN (%s)" % D))
    CV = lit(col_ids(cur, "SELECT id FROM chat_conversation"
                         " WHERE member_a IN (%s) OR member_b IN (%s)" % (D, D)))
    print("\n派生内容：帖 %s / 其评论 %s / 集市 %s / 其留言 %s / 群 %s / 圈子 %s"
          " / 搭话 %s / 活动 %s / 会话 %s"
          % (P, CM, M, MC, G, CI, B, ACT, CV))

    # 每张表的 WHERE：既删「他做的」，也删「冲他来的」，否则对面那侧留幽灵。
    W = {
        "notify": "user_id IN (%s) OR actor_id IN (%s)" % (D, D),
        "feed_item": "fan_id IN (%s) OR post_id IN (%s)" % (D, P),
        "post_topic": "post_id IN (%s)" % P,
        "review_task": "post_id IN (%s) OR reviewer_id IN (%s)" % (P, D),
        "comment": "user_id IN (%s) OR post_id IN (%s) OR parent_id IN (%s)" % (D, P, CM),
        "like": "user_id IN (%s) OR (target_type = 1 AND target_id IN (%s))" % (D, P)
                + " OR (target_type = 2 AND target_id IN (%s))" % CM,
        "post_favorite": "user_id IN (%s) OR post_id IN (%s)" % (D, P),
        "report": "reporter_id IN (%s) OR handler_id IN (%s)" % (D, D),
        "relation": "from_user_id IN (%s) OR to_user_id IN (%s)" % (D, D),
        "user_tag": "user_id IN (%s)" % D,
        "chat_message": "sender_id IN (%s) OR receiver_id IN (%s) OR conv_id IN (%s)" % (D, D, CV),
        # kind 取值见 schema.sql:242 —— dm 的 target_id 是对方 user_id,
        # group 的是 group.id。保留方对已删账号/已删群的偏好也要一起清。
        "chat_conv_pref": "user_id IN (%s) OR (kind = 'dm' AND target_id IN (%s))" % (D, D)
                          + " OR (kind = 'group' AND target_id IN (%s))" % G,
        "chat_conversation": "member_a IN (%s) OR member_b IN (%s)" % (D, D),
        "group_message": "sender_id IN (%s) OR group_id IN (%s)" % (D, G),
        "group_read": "user_id IN (%s) OR group_id IN (%s)" % (D, G),
        "group_member": "user_id IN (%s) OR group_id IN (%s)" % (D, G),
        "group": "owner_id IN (%s)" % D,
        "circle_member": "user_id IN (%s) OR circle_id IN (%s)" % (D, CI),
        "circle": "owner_id IN (%s)" % D,
        "mkt_comment": "user_id IN (%s) OR activity_id IN (%s)" % (D, M),
        "mkt_activity": "user_id IN (%s) OR id IN (%s)" % (D, M),
        "activity_signup": "user_id IN (%s) OR activity_id IN (%s)" % (D, ACT),
        "activity": "creator_id IN (%s) OR id IN (%s)" % (D, ACT),
        "buddy_join": "user_id IN (%s) OR buddy_id IN (%s)" % (D, B),
        "buddy_post": "user_id IN (%s) OR id IN (%s)" % (D, B),
        "announcement_read": "user_id IN (%s)" % D,
        "feedback": "user_id IN (%s)" % D,
        "upload_file": "user_id IN (%s)" % D,
        "post": "author_id IN (%s) OR id IN (%s)" % (D, P),
        "user": "id IN (%s)" % D,
        "audit_log": "user_id IN (%s)" % D,
    }
    # 顺序：内容 → 互动 → 关系 → 群/圈/会话 → 集市/活动/搭话 → 帖 → 账号。
    ORDER = ["notify", "feed_item", "post_topic", "review_task", "comment", "like",
             "post_favorite", "report", "relation", "user_tag", "chat_message",
             "chat_conv_pref", "chat_conversation", "group_message", "group_read",
             "group_member", "group", "circle_member", "circle", "mkt_comment",
             "mkt_activity", "activity_signup", "activity", "buddy_join", "buddy_post",
             "announcement_read", "feedback", "upload_file", "post", "user"]
    tables = list(ORDER)
    if not a.wipe_audit:
        print("\n提示：audit_log 默认保留（安全审计留痕）。要一起删加 --wipe-audit。")
    else:
        tables.append("audit_log")

    missing = [t for t in W if t not in tables]
    if missing and not a.wipe_audit:
        assert missing == ["audit_log"], "有表未纳入删除顺序: %s" % missing

    total = 0
    errs = []
    # 第二个清理维度：全库 @TableLogic 软删残留。应用层查不到，但 mysqldump
    # 会把它们原样带进服务器 —— 走「本地库导入」路线时必须一起物理删。
    purge = []
    if a.purge_deleted:
        cur.execute("SELECT table_name FROM information_schema.columns"
                    " WHERE table_schema=%s AND column_name='is_deleted'"
                    " ORDER BY table_name", (db,))
        purge = [(r[list(r.keys())[0]], "is_deleted = 1") for r in cur.fetchall()]
    plan = [(t, W[t]) for t in tables] + purge
    print("\n%-22s %6s  %s" % ("表", "命中", "WHERE 条件"))
    for t, w in plan:
        try:
            n = scalar(cur, "SELECT COUNT(*) n FROM `%s` WHERE %s" % (t, w))
        except Exception as e:
            # 列名写错时不崩在中间：把这张表记下来，末尾统一报，避免给出半张清单。
            c.rollback()
            errs.append((t, str(e)[:90]))
            print("  %-20s %6s  查询失败: %s" % (t, "-", str(e)[:70]))
            continue
        total += n
        print("  %-20s %6d  %s" % (t, n, w[:72]))
    print("  %-20s %6d" % ("合计", total))
    if errs:
        c.close()
        sys.exit("有 %d 张表的 WHERE 条件与实际列名不符，清单不完整，先修正再执行：%s"
                 % (len(errs), "; ".join("%s: %s" % x for x in errs)))

    if not a.apply:
        c.close()
        print("\ndry-run 结束，未改动任何数据。"
              "\n确认无误后：mysqldump 备份 → 加 --backup <备份文件> --apply")
        return

    if not a.backup or not os.path.exists(a.backup):
        c.close()
        sys.exit("拒绝执行：--backup 必须指向已存在的备份文件。先跑："
                 "\n  mysqldump -u root -p --single-transaction %s > 备份.sql" % db)

    print("\n开始执行（单事务，失败整体回滚）…")
    try:
        for t, w in plan:
            cur.execute("DELETE FROM `%s` WHERE %s" % (t, w))
            print("  删 %-20s %d 行" % (t, cur.rowcount))
        c.commit()
    except Exception as e:
        c.rollback()
        c.close()
        sys.exit("执行失败，已整体回滚： %s" % e)

    print("\n孤儿审计（应全为 0）：")
    bad = 0
    for t in sorted(USER_COLS):
        for cn in USER_COLS[t]:
            try:
                n = scalar(cur,
                           "SELECT COUNT(*) n FROM `%s` x WHERE x.`%s` IS NOT NULL"
                           " AND NOT EXISTS (SELECT 1 FROM user u WHERE u.id = x.`%s`)"
                           % (t, cn, cn))
            except Exception as e:
                print("  ?? %s.%s 查询异常: %s" % (t, cn, str(e)[:60]))
                bad += 1
                continue
            if n:
                print("  !! %s.%s 仍有 %d 行指向不存在的用户" % (t, cn, n))
                bad += n
    print("  孤儿行合计: %d" % bad)
    c.close()

    print("\n下一步（按顺序）：")
    print("  1) 启动后端一次，让 ReconcileJob 重算 user/post/circle 计数（或等凌晨 3 点）。")
    print("  2) redis-cli INCR feed:p1:ver        # 首页首屏缓存按版本号失效")
    print("  3) 清测试期撞库计数：redis-cli --scan --pattern 'login:fail:*' 后逐条 DEL")
    print("  4) 给保留账号改强口令（种子脚本里的 user123/admin123 已是公开值）。")
    print("  5) 上线只导 schema.sql + sql/migration_sensitive_words.sql，"
          "不要导 sql/seed_data.sql。")


if __name__ == "__main__":
    main()
