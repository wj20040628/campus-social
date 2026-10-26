# -*- coding: utf-8 -*-
"""清空全部内容数据，只保留指定账号本体与字典表。默认 dry-run，不写库。

与 clean_test_data.py 的区别：那个按「人」删（删测试账号及其牵连行），
这个按「库」删（内容全部归零，账号与字典留着），用于上线前拿一个干净站。

保留边界（关键：这些不是用户数据，删了站就废）：
  sensitive_word  敏感词库 154 条 —— DFA 自动闸门的弹药，删了审核空转
  school_dept / school_grade / school_major  学院年级专业字典 —— 注册引导页
                                             与个人主页「人工智能学院·2023级·大四」的数据源
  interest_tag    兴趣标签字典 60 条 —— user_tag.tag_id 指向它
  topic           话题字典
  reserved_circle_name  保留圈名
  upload_file     上传台账 —— 头像/封面靠它做归属与孤儿文件回收
  user            仅 --keep 指定的行（含 avatar_url/cover_url/verify_status/notify_pref）
  user_tag        仅 --keep 指定用户的兴趣标签

其余表全部 TRUNCATE。TRUNCATE 是 DDL、隐式提交、不能回滚，所以必须
先有 mysqldump 备份（脚本会校验备份文件真实存在，否则拒绝执行）。

用法：
  python wipe_content_keep_accounts.py --keep 1,2
  python wipe_content_keep_accounts.py --keep 1,2 --backup D:\\bk.sql --apply
"""
import argparse
import os
import re
import sys

import pymysql

KEEP_WHOLE = {
    "sensitive_word", "school_dept", "school_grade", "school_major",
    "interest_tag", "topic", "reserved_circle_name", "upload_file",
}
# 只保留部分行的表 -> 过滤条件模板
KEEP_FILTERED = {
    "user": "id IN ({keep})",
    "user_tag": "user_id IN ({keep})",
}
# 内容归零后必须回填的冗余计数列。判据不另立规矩，逐条对齐项目里的权威出处：
#   follower/following  -> UserMapper.reconcileAll（relation 且 type=1 且未软删）
#   joined_circle_count -> sql/migration_f11_joined_circle_count.sql 的存量回填语句
#   unread_notify_count -> notify.status=0 未读
#   interest_tag.use_count / topic.post_count -> schema.sql:608 与 ±1 维护口径
# 注意：ReconcileJob 只重算 follower/following 两列，joined_circle_count 与
# unread_notify_count 不在其覆盖范围内，所以这里必须自己做，不能等凌晨对账。
RESETS = [
    ("user", "follower_count / following_count（relation type=1）",
     "UPDATE user u SET "
     " follower_count = (SELECT COUNT(*) FROM relation r WHERE r.to_user_id = u.id"
     " AND r.type = 1 AND r.is_deleted = 0),"
     " following_count = (SELECT COUNT(*) FROM relation r WHERE r.from_user_id = u.id"
     " AND r.type = 1 AND r.is_deleted = 0)"),
    ("user", "joined_circle_count（circle_member 未软删）",
     "UPDATE user u SET joined_circle_count = (SELECT COUNT(*) FROM circle_member cm"
     " WHERE cm.user_id = u.id AND cm.is_deleted = 0)"),
    ("user", "unread_notify_count（notify.is_read=0 未读）",
     "UPDATE user u SET unread_notify_count = (SELECT COUNT(*) FROM notify n"
     " WHERE n.user_id = u.id AND n.is_read = 0 AND n.is_deleted = 0)"),
    # use_count 不能直接置 0：user_tag 里保留账号的 17 行还在，
    # 置 0 会让「发现同好」按 use_count 排序时永久错位
    # （TagServiceImpl 注释里点过这个坑）。
    ("interest_tag", "use_count（按存活的 user_tag 重算，不是置 0）",
     "UPDATE interest_tag t SET use_count = (SELECT COUNT(*) FROM user_tag ut"
     " WHERE ut.tag_id = t.id AND ut.is_deleted = 0)"),
    ("topic", "post_count（按存活的 post_topic 未软删行重算）",
     "UPDATE topic t SET post_count = (SELECT COUNT(*) FROM post_topic pt"
     " WHERE pt.topic_id = t.id AND pt.is_deleted = 0)"),
]


def connect():
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


def scalar(cur, sql):
    cur.execute(sql)
    return list(cur.fetchone().values())[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", required=True, help="保留的账号 id，逗号分隔，如 1,2")
    ap.add_argument("--apply", action="store_true", help="真正执行（默认只打印计划）")
    ap.add_argument("--backup", help="--apply 时必填：已存在的 mysqldump 备份文件")
    ap.add_argument("--wipe-audit", action="store_true",
                    help="连 audit_log 一起清（默认保留上线前的操作留痕）")
    a = ap.parse_args()

    keep_ids = sorted({int(x) for x in a.keep.split(",") if x.strip()})
    if not keep_ids:
        sys.exit("--keep 不能为空")
    kl = ",".join(str(x) for x in keep_ids)

    c = connect()
    cur = c.cursor()
    db = c.db.decode() if isinstance(c.db, bytes) else (c.db or "")

    cur.execute("SELECT id,username,nickname,role FROM user WHERE id IN (%s) ORDER BY id" % kl)
    kept = cur.fetchall()
    if len(kept) != len(keep_ids):
        c.close()
        sys.exit("--keep 里有 id 在 user 表不存在，请核对后再跑")
    if not any(r["role"] == 1 for r in kept):
        c.close()
        sys.exit("拒绝执行：--keep 里没有 role=1 的管理员，清完后台将无人可进")
    print("保留账号：")
    for r in kept:
        print("  id=%-4s role=%s %-24s %s" % (r["id"], r["role"], r["username"], r["nickname"]))

    cur.execute("SELECT table_name FROM information_schema.tables"
                " WHERE table_schema=%s AND table_type='BASE TABLE' ORDER BY table_name", (db,))
    all_tabs = [list(r.values())[0] for r in cur.fetchall()]

    wipe, keep, partial = [], [], []
    for t in all_tabs:
        if t in KEEP_WHOLE:
            keep.append(t)
        elif t in KEEP_FILTERED:
            partial.append(t)
        elif t == "audit_log" and not a.wipe_audit:
            keep.append(t)
        else:
            wipe.append(t)

    print("\n%-24s %8s  %s" % ("表", "行数", "动作"))
    doomed_rows = 0
    for t in wipe:
        n = scalar(cur, "SELECT COUNT(*) c FROM `%s`" % t)
        doomed_rows += n
        print("  %-22s %8d  TRUNCATE（整表清空）" % (t, n))
    for t in partial:
        cond = KEEP_FILTERED[t].format(keep=kl)
        n = scalar(cur, "SELECT COUNT(*) c FROM `%s`" % t)
        left = scalar(cur, "SELECT COUNT(*) c FROM `%s` WHERE %s" % (t, cond))
        doomed_rows += n - left
        print("  %-22s %8d  留 %d 行，删 %d 行" % (t, n, left, n - left))
    for t in keep:
        n = scalar(cur, "SELECT COUNT(*) c FROM `%s`" % t)
        print("  %-22s %8d  保留" % (t, n))
    print("  %-22s %8d  待删行数合计（全库 %d 行）"
          % ("", doomed_rows,
             sum(scalar(cur, "SELECT COUNT(*) c FROM `%s`" % t) for t in all_tabs)))

    print("\n计数回填（判据对齐项目权威出处，见 RESETS 注释）：")
    for t, why, sql in RESETS:
        print("  %-14s %s" % (t, why))

    if not a.apply:
        c.close()
        print("\ndry-run 结束，未改动任何数据。"
              "\n下一步：mysqldump --single-transaction %s > 备份.sql，"
              "再加 --backup 该文件 --apply" % db)
        return

    if not a.backup or not os.path.exists(a.backup):
        c.close()
        sys.exit("拒绝执行：TRUNCATE 不可回滚，--backup 必须指向已存在的备份文件")

    print("\n开始执行…")
    for t in wipe:
        cur.execute("TRUNCATE TABLE `%s`" % t)
        print("  清空 %-20s auto_increment 已重置" % t)
    for t in partial:
        cond = KEEP_FILTERED[t].format(keep=kl)
        cur.execute("DELETE FROM `%s` WHERE NOT (%s)" % (t, cond))
        print("  删   %-20s %d 行（保留 %s）" % (t, cur.rowcount, cond))
    for t, _why, sql in RESETS:
        cur.execute(sql)
        print("  归零 %-20s %d 行" % (t, cur.rowcount))
    c.commit()
    c.close()

    print("\n完成。收尾三件事：")
    print("  1) 别删磁盘上的 uploads 目录：保留账号的头像/封面是真实文件，"
          "TRUNCATE 不会动它们，删了目录头像就裂。")
    print("  2) redis-cli INCR feed:p1:ver        # 首页首屏缓存按版本号失效")
    print("     redis-cli --scan --pattern 'login:fail:*' 逐条 DEL")
    print("     redis-cli --scan --pattern 'user:status:*' 逐条 DEL")
    print("  3) 给保留账号改强口令（种子脚本里的 user123/admin123 是公开值）。")


if __name__ == "__main__":
    main()
