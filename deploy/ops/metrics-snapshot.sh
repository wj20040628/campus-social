#!/bin/bash
# 采集本机 Actuator Prometheus 指标快照（2026-11-01）。
#
# 线上安装位置：/opt/campus/metrics-snapshot.sh（root 0700）
# 由 campus-metrics.timer 每 5 分钟执行；只保留最近 8 天。
# 指标端点不经过 Nginx，必须从 ECS 本机 127.0.0.1 访问。
set -euo pipefail
umask 077

BASE_URL="${METRICS_BASE_URL:-http://127.0.0.1:8080}"
OUT_DIR="${METRICS_OUT_DIR:-/opt/campus/logs/metrics}"
KEEP_DAYS="${METRICS_KEEP_DAYS:-8}"
FILE="$OUT_DIR/metrics-$(date +%F).prom"

# 只留排障要看的家族，避免把全部 JVM/HTTP 直方图明细每天写成几百 MB。
# HTTP 只保留 count/sum 与已计算的 p50/p95/p99（quantile），不保留下钻桶。
FILTER='^(http_server_requests_seconds(_count|_sum)\{|http_server_requests_seconds\{[^}]*quantile=|hikaricp_connections_(active|idle|pending|max|timeout_total)\{|jvm_memory_used_bytes\{|jvm_gc_pause_seconds_(count|sum)\{|system_cpu_usage\{|process_cpu_usage\{|tomcat_threads_(busy|current|config_max)\{|campus_rate_limit_(blocked|fail_open)_total\{)'

mkdir -p "$OUT_DIR"

TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT
if ! curl -fsS --max-time 10 "$BASE_URL/actuator/prometheus" -o "$TMP"; then
    echo "# $(date -Iseconds) scrape failed: $BASE_URL" >> "$FILE"
    exit 1
fi

# grep 无匹配时返回 1，但抓取本身成功；只过滤不改变这次采样的成败。
grep -E "$FILTER" "$TMP" >> "$FILE" || true
echo "# scrape $(date -Iseconds)" >> "$FILE"
find "$OUT_DIR" -name 'metrics-*.prom' -mtime +"$KEEP_DAYS" -delete
