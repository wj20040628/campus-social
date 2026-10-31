#!/bin/bash
# 生产恢复演练：把最新备份还原到独立临时库，核对结构、索引、外键与数据。
# 线上安装位置：/opt/campus/restore-check.sh（root 0700）
# 用法：/opt/campus/restore-check.sh [备份文件]
# 默认取 /opt/campus/backup 下最新的 campus_social_v2_*.sql.gz
set -euo pipefail
umask 077

BACKUP_DIR=/opt/campus/backup
if [ -n "${1:-}" ]; then
  BACKUP="$1"
else
  BACKUP=$(ls -1t "$BACKUP_DIR"/campus_social_v2_*.sql.gz 2>/dev/null | head -n1 || true)
fi
EXPECTED_TABLES="${EXPECTED_TABLES:-39}"
STAMP=$(date +%Y%m%d_%H%M%S)
DB="campus_restore_check_${STAMP}_$$"
TMP_USER="rc_${STAMP}_$$"
TMP_PASS=$(openssl rand -hex 16)
LOG="$BACKUP_DIR/restore-check.log"
START_EPOCH=$(date +%s)
START_HUMAN=$(date '+%F %T %Z')
USER_CREATED=0

log() {
  printf '%s | %s\n' "$(date '+%F %T %Z')" "$*" | tee -a "$LOG"
}

fail() {
  log "恢复演练失败: $*"
  exit 1
}

cleanup() {
  if [ "$USER_CREATED" = "1" ]; then
    mysql -uroot -e "DROP USER IF EXISTS '$TMP_USER'@'127.0.0.1';" >/dev/null 2>&1 || true
  fi
  case "$DB" in
    campus_restore_check_*)
      mysql -uroot -e "DROP DATABASE IF EXISTS \`$DB\`;" >/dev/null 2>&1 || true
      ;;
  esac
}
trap cleanup EXIT

case "$DB" in
  campus_restore_check_*) ;;
  *) fail "临时库名异常，拒绝继续: $DB" ;;
esac

[ -n "$BACKUP" ] && [ -f "$BACKUP" ] || fail "找不到备份文件，用法: $0 [备份文件]"
[ -r "$BACKUP" ] || fail "备份文件不可读: $BACKUP"
gzip -t "$BACKUP" 2>/dev/null || fail "gzip 校验失败: $BACKUP"

log "开始恢复演练: backup=$BACKUP size=$(du -h "$BACKUP" | cut -f1) temp_db=$DB expected_tables=$EXPECTED_TABLES"

curl -fsS -m 5 http://127.0.0.1:8080/actuator/health 2>/dev/null \
  | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
  || fail "演练前生产应用 health 不是 UP"

mysql -uroot -e "CREATE DATABASE \`$DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" \
  || fail "创建临时库失败"

if ! gunzip -c "$BACKUP" | mysql -uroot --default-character-set=utf8mb4 "$DB"; then
  fail "导入失败"
fi

TABLES=$(mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB';")
[ "$TABLES" = "$EXPECTED_TABLES" ] || fail "表数量不符: 实际 $TABLES / 预期 $EXPECTED_TABLES"
log "表数量: $TABLES/$EXPECTED_TABLES"

KEY_TABLES=(user post comment notify report sensitive_word audit_log file_asset circle group group_member group_message chat_conversation chat_message)
for table in "${KEY_TABLES[@]}"; do
  exists=$(mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$table';")
  [ "$exists" = "1" ] || fail "缺少关键表: $table"
done
log "关键表: ${#KEY_TABLES[@]}/${#KEY_TABLES[@]} 存在"

check_index() {
  local table=$1 index=$2
  local count
  count=$(mysql -uroot -N -e "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DB' AND table_name='$table' AND index_name='$index';")
  [ "$count" = "1" ] || fail "缺少索引: $table.$index"
}
check_index user uk_username
check_index post ft_content
check_index like uk_like
check_index chat_conversation uk_conv
check_index group_member uk_gm
check_index file_asset idx_status_id
check_index school_major idx_dept
log "关键索引: 7/7 存在"

FK_COUNT=$(mysql -uroot -N -e "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='$DB';")
if [ "$FK_COUNT" -gt 0 ]; then
  log "外键: $FK_COUNT 个存在"
else
  log "提示: 恢复库没有外键；sql/schema.sql 定义的 school_major.fk_major_dept 未在生产落地（已知 schema 漂移），本次只记录、不判失败"
fi

count_of() {
  mysql -uroot -N -e "SELECT COUNT(*) FROM \`$DB\`.\`$1\`;"
}
USER_ROWS=$(count_of user)
POST_ROWS=$(count_of post)
COMMENT_ROWS=$(count_of comment)
NOTIFY_ROWS=$(count_of notify)
REPORT_ROWS=$(count_of report)
WORD_ROWS=$(count_of sensitive_word)
FILE_ROWS=$(count_of file_asset)
log "抽查行数: user=$USER_ROWS post=$POST_ROWS comment=$COMMENT_ROWS notify=$NOTIFY_ROWS report=$REPORT_ROWS sensitive_word=$WORD_ROWS file_asset=$FILE_ROWS"
[ "$USER_ROWS" -ge 1 ] || fail "user 表没有数据"
[ "$WORD_ROWS" -ge 1 ] || fail "sensitive_word 表没有数据"

mysql -uroot -e "CREATE USER '$TMP_USER'@'127.0.0.1' IDENTIFIED BY '$TMP_PASS'; GRANT SELECT ON \`$DB\`.* TO '$TMP_USER'@'127.0.0.1';" \
  || fail "创建临时只读账号失败"
USER_CREATED=1
MYSQL_PWD="$TMP_PASS" mysql -h127.0.0.1 -P3306 -u"$TMP_USER" -N -e "SELECT COUNT(*) FROM \`$DB\`.user;" >/dev/null \
  || fail "临时账号无法通过 TCP 连接恢复库"
log "应用侧连接: 临时只读账号 TCP 查询恢复库成功"

curl -fsS -m 5 http://127.0.0.1:8080/actuator/health 2>/dev/null \
  | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"' \
  || fail "演练后生产应用 health 不是 UP"

END_EPOCH=$(date +%s)
DURATION=$((END_EPOCH - START_EPOCH))
log "恢复演练通过: ${START_HUMAN} -> $(date '+%F %T %Z')，耗时 ${DURATION}s"
