#!/bin/bash
# 线上安装位置：/opt/campus/backup-watchdog.sh（root:root 0700）
# 由 campus-backup-watchdog.timer 每小时调用；检查最近一次成功备份是否超过 26 小时。
# 告警地址复用 /opt/campus/.env 的 ALERT_WEBHOOK_URL；同一故障 24 小时内只发一次。
set -uo pipefail
umask 077

DIR="${BACKUP_DIR:-/opt/campus/backup}"
SUCCESS_MARK="$DIR/.last_success"
STALE_MARK="$DIR/.last_stale_alert"
LOG="$DIR/backup.log"
THRESHOLD_SECONDS="${BACKUP_MAX_AGE_SECONDS:-93600}" # 26 小时
ENV_FILE="${ENV_FILE:-/opt/campus/.env}"

mkdir -p "$DIR"
if [ -f "$ENV_FILE" ]; then
  set -a
  . "$ENV_FILE"
  set +a
fi

alert() {
  local msg="友声备份告警 | $1"
  printf '%s | %s\n' "$(date '+%F %T %Z')" "$msg" >> "$LOG"
  command -v logger >/dev/null 2>&1 && logger -t campus-backup-watchdog "$msg" 2>/dev/null || true
  if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
    local json
    json=$(python3 -c 'import json,sys; print(json.dumps({"msgtype":"text","text":{"content":sys.argv[1]}}, ensure_ascii=False))' "$msg" 2>/dev/null) || json=""
    if [ -n "$json" ]; then
      curl -sS --max-time 10 -H 'Content-Type: application/json' -d "$json" "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 \
        || command -v logger >/dev/null 2>&1 && logger -t campus-backup-watchdog "webhook 告警发送失败" 2>/dev/null || true
    fi
  fi
}

now=$(date +%s)
if [ ! -f "$SUCCESS_MARK" ]; then
  msg="从未检测到成功备份标记（$SUCCESS_MARK）"
else
  mtime=$(stat -c %Y "$SUCCESS_MARK" 2>/dev/null || echo 0)
  age=$((now - mtime))
  if [ "$age" -le "$THRESHOLD_SECONDS" ]; then
    exit 0
  fi
  msg="最近成功备份距今 ${age}s，超过阈值 ${THRESHOLD_SECONDS}s（约 26 小时）"
fi

# 同一故障 24 小时内只提醒一次，避免每小时刷屏
if [ -f "$STALE_MARK" ]; then
  last=$(stat -c %Y "$STALE_MARK" 2>/dev/null || echo 0)
  if [ $((now - last)) -lt 86400 ]; then
    exit 0
  fi
fi
printf '%s | %s\n' "$(date '+%F %T %Z')" "$msg" > "$STALE_MARK"
alert "$msg"
echo "$msg" >&2
exit 1
