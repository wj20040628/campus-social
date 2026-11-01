#!/bin/bash
# 安装友声性能指标采样（2026-11-01）。
#
# 在 ECS 上以 root 执行；安装位置：
#   /opt/campus/metrics-snapshot.sh
#   /etc/systemd/system/campus-metrics.{service,timer}
# 采样结果：/opt/campus/logs/metrics/metrics-YYYY-MM-DD.prom（保留 8 天）。
#
# 前提：新 jar 已上线（含 /actuator/prometheus），且 8080 只在本机监听/仅 Nginx 可达。
set -euo pipefail

SRC=$(cd "$(dirname "$0")" && pwd)
UNIT_SRC="$SRC/../systemd"
if [ ! -f "$UNIT_SRC/campus-metrics.service" ] && [ -f "$SRC/campus-metrics.service" ]; then
    UNIT_SRC="$SRC"
fi

for f in metrics-snapshot.sh; do
    [ -f "$SRC/$f" ] || { echo "缺少 $SRC/$f" >&2; exit 1; }
done
for unit in campus-metrics.service campus-metrics.timer; do
    [ -f "$UNIT_SRC/$unit" ] || { echo "缺少 $UNIT_SRC/$unit" >&2; exit 1; }
done

install -o root -g root -m 0700 "$SRC/metrics-snapshot.sh" /opt/campus/metrics-snapshot.sh
mkdir -p /opt/campus/logs/metrics
chmod 700 /opt/campus/logs/metrics

install -o root -g root -m 0644 "$UNIT_SRC/campus-metrics.service" /etc/systemd/system/campus-metrics.service
install -o root -g root -m 0644 "$UNIT_SRC/campus-metrics.timer" /etc/systemd/system/campus-metrics.timer

systemctl daemon-reload
systemctl enable campus-metrics.timer
systemctl start campus-metrics.timer

# 立即跑一次，确认端点与权限都没问题
systemctl start campus-metrics.service
systemctl --no-pager status campus-metrics.service | head -n 12 || true

echo
echo "定时器状态："
systemctl list-timers campus-metrics.timer --no-pager
echo
echo "今日采样文件：/opt/campus/logs/metrics/metrics-$(date +%F).prom"
