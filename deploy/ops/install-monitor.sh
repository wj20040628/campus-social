#!/bin/bash
# 安装应用层巡检。把 app_monitor.py 与 campus-monitor.{service,timer} 放在同一目录，
# 或把 systemd 单元放在 ../systemd/，在 ECS 上以 root 执行。
set -euo pipefail

SRC=$(cd "$(dirname "$0")" && pwd)
UNIT_DIR="$SRC"
for unit in campus-monitor.service campus-monitor.timer; do
  if [ ! -f "$UNIT_DIR/$unit" ] && [ -f "$SRC/../systemd/$unit" ]; then
    UNIT_DIR="$SRC/../systemd"
  fi
done

[ "$(id -u)" = "0" ] || { echo "需要 root 执行" >&2; exit 1; }
[ -f "$SRC/app_monitor.py" ] || { echo "缺少 app_monitor.py" >&2; exit 1; }
[ -f "$UNIT_DIR/campus-monitor.service" ] || { echo "缺少 campus-monitor.service" >&2; exit 1; }
[ -f "$UNIT_DIR/campus-monitor.timer" ] || { echo "缺少 campus-monitor.timer" >&2; exit 1; }
command -v python3 >/dev/null || { echo "缺少 python3" >&2; exit 1; }

install -o root -g root -m 0700 "$SRC/app_monitor.py" /opt/campus/app_monitor.py
install -o root -g root -m 0644 "$UNIT_DIR/campus-monitor.service" /etc/systemd/system/campus-monitor.service
install -o root -g root -m 0644 "$UNIT_DIR/campus-monitor.timer" /etc/systemd/system/campus-monitor.timer

if ! grep -q '^ALERT_WEBHOOK_URL=' /opt/campus/.env 2>/dev/null; then
  echo "警告: /opt/campus/.env 没有 ALERT_WEBHOOK_URL，告警只会写日志" >&2
fi
if [ ! -r /var/log/nginx/access.log ]; then
  echo "警告: /var/log/nginx/access.log 不可读，5xx 巡检会跳过" >&2
fi

systemctl daemon-reload
systemctl enable campus-monitor.timer
systemctl restart campus-monitor.timer
systemctl start campus-monitor.service
systemctl is-enabled campus-monitor.timer
systemctl list-timers campus-monitor.timer --no-pager
tail -n 20 /var/log/campus-monitor.log 2>/dev/null || true
