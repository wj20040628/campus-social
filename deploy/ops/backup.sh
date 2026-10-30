#!/bin/bash
# 线上安装位置：/opt/campus/backup.sh（root:root 0700）
# 由 campus-backup.timer 每天 04:30 调用；备份目录 /opt/campus/backup 0700，文件 0600
# 失败告警：在 /opt/campus/.env 设置 ALERT_WEBHOOK_URL（钉钉/企业微信群机器人）
# 每日备份 campus_social_v2：本地保留 14 天 + 上传 OSS logs/db/ 异地留存
# 2026-10-30：临时文件 + 原子落盘、成功/失败标记、失败 Webhook 告警、并发锁
# 2026-10-30：每月 1 日额外上传 logs/db-monthly/YYYY-MM.sql.gz，保留 12 个月
set -uo pipefail
umask 077

DIR=/opt/campus/backup
STAMP=$(date +%Y%m%d_%H%M%S)
OUT="$DIR/campus_social_v2_${STAMP}.sql.gz"
PART="$DIR/.campus_social_v2_${STAMP}.sql.gz.part"
ERR="$DIR/.backup_${STAMP}.err"
OSS_KEY="oss://yousheng-uploads/logs/db/${STAMP}.sql.gz"
SUCCESS_MARK="$DIR/.last_success"
FAIL_MARK="$DIR/.last_failure"
LOG="$DIR/backup.log"

mkdir -p "$DIR"
exec 9>/var/lock/campus-backup.lock
if ! flock -n 9; then
  echo "备份失败：已有备份任务在运行" >&2
  exit 1
fi
find "$DIR" -maxdepth 1 -name '.campus_social_v2_*.sql.gz.part' -mtime +1 -delete 2>/dev/null || true

set -a
. /opt/campus/.env
set +a
export MYSQL_PWD="$MYSQL_PASSWORD"

alert() {
  local msg="友声备份告警 | $1"
  printf '%s | %s\n' "$(date '+%F %T %Z')" "$msg" > "$FAIL_MARK"
  printf '%s | %s\n' "$(date '+%F %T %Z')" "$msg" >> "$LOG"
  logger -t campus-backup "$msg" 2>/dev/null || true
  if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    local json
    json=$(python3 -c 'import json,sys; print(json.dumps({"msgtype":"text","text":{"content":sys.argv[1]}}, ensure_ascii=False))' "$msg" 2>/dev/null) || json=""
    if [ -n "$json" ]; then
      curl -sS --max-time 10 -H 'Content-Type: application/json' -d "$json" "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 \
        || logger -t campus-backup "webhook 告警发送失败" 2>/dev/null || true
    else
      logger -t campus-backup "webhook JSON 生成失败" 2>/dev/null || true
    fi
  fi
}

mysqldump -u"${MYSQL_USER:-campus}" --default-character-set=utf8mb4 --single-transaction --quick \
  --no-tablespaces --routines --triggers --events campus_social_v2 2>"$ERR" | gzip -1 > "$PART"
RC=("${PIPESTATUS[@]}")
if [ "${RC[0]:-1}" -ne 0 ] || [ "${RC[1]:-1}" -ne 0 ]; then
  MSG="备份失败：mysqldump/gzip 执行失败（mysqldump=${RC[0]:-?}, gzip=${RC[1]:-?}）$(tail -n 2 "$ERR" 2>/dev/null | tr '\n' ' ')"
  rm -f "$PART" "$ERR"
  alert "$MSG"
  echo "$MSG" >&2
  exit 1
fi
if [ ! -s "$PART" ] || ! gzip -t "$PART" 2>/dev/null || ! gzip -dc "$PART" 2>/dev/null | grep 'CREATE TABLE' >/dev/null; then
  MSG="备份失败：文件为空、gzip 损坏或不含建表语句"
  rm -f "$PART" "$ERR"
  alert "$MSG"
  echo "$MSG" >&2
  exit 1
fi
SIZE=$(du -h "$PART" | cut -f1)
if ! /usr/local/bin/ossutil cp "$PART" "$OSS_KEY" \
      -e oss-cn-hangzhou.aliyuncs.com --region cn-hangzhou --mode EcsRamRole >/dev/null 2>>"$ERR"; then
  mv "$PART" "$OUT"
  MSG="本地备份成功但上传 OSS 失败: $OUT ($SIZE) $(tail -n 1 "$ERR" 2>/dev/null)"
  rm -f "$ERR"
  alert "$MSG"
  echo "$MSG" >&2
  exit 1
fi
mv "$PART" "$OUT"
rm -f "$ERR"
find "$DIR" -name "campus_social_v2_*.sql.gz" -mtime +14 -delete
printf '%s | %s | %s | %s\n' "$(date '+%F %T %Z')" "$OUT" "$SIZE" "$OSS_KEY" > "$SUCCESS_MARK"
rm -f "$FAIL_MARK"
printf '%s | 备份成功: %s (%s) -> %s\n' "$(date '+%F %T %Z')" "$OUT" "$SIZE" "$OSS_KEY" >> "$LOG"
echo "备份成功: $OUT ($SIZE) -> $OSS_KEY"

# 每月 1 日额外保留一份月度副本，供 12 个月长期回看；日用副本仍只留 14 天
if [ "$(date +%d)" = "01" ]; then
  MONTH_KEY="oss://yousheng-uploads/logs/db-monthly/$(date +%Y-%m).sql.gz"
  MONTH_ERR="$DIR/.backup_monthly.err"
  if /usr/local/bin/ossutil cp "$OUT" "$MONTH_KEY" \
        -e oss-cn-hangzhou.aliyuncs.com --region cn-hangzhou --mode EcsRamRole >/dev/null 2>>"$MONTH_ERR"; then
    rm -f "$MONTH_ERR"
    printf '%s | 月度备份: %s -> %s\n' "$(date '+%F %T %Z')" "$OUT" "$MONTH_KEY" >> "$LOG"
  else
    MSG="月度备份上传失败: $MONTH_KEY $(tail -n 1 "$MONTH_ERR" 2>/dev/null)"
    rm -f "$MONTH_ERR"
    alert "$MSG"
    echo "$MSG" >&2
    exit 1
  fi
fi
