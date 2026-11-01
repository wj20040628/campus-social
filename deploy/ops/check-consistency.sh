#!/usr/bin/env bash
# 部署后一致性闸门：返回非 0 则停止启动/部署（2026-11-01，1.13.11）
# 用法：
#   bash check-consistency.sh [consistency_check.sql 路径]
# 默认读取 /opt/campus/.env 的 MYSQL_*，库名 campus_social_v2。
set -euo pipefail

BASE="${BASE:-/opt/campus}"
DB_NAME="${DB_NAME:-campus_social_v2}"
SQL="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/../sql/consistency_check.sql}"

[ -f "$BASE/.env" ] || { echo "错误：缺少 $BASE/.env" >&2; exit 1; }
[ -f "$SQL" ] || { echo "错误：缺少校验 SQL $SQL" >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. "$BASE/.env"
set +a
: "${MYSQL_USER:?/opt/campus/.env 缺少 MYSQL_USER}"
: "${MYSQL_PASSWORD:?/opt/campus/.env 缺少 MYSQL_PASSWORD}"
export MYSQL_PWD="$MYSQL_PASSWORD"
DB_HOST="${MYSQL_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-3306}"

VIOLATIONS="$(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" \
  --default-character-set=utf8mb4 -N -B "$DB_NAME" < "$SQL" | tr -d '[:space:]')"

if [ "$VIOLATIONS" != "0" ]; then
  echo "一致性校验未通过：violations=$VIOLATIONS" >&2
  echo "请先执行存量修复脚本并核对 sql/consistency_check.sql 的明细查询。" >&2
  exit 1
fi
echo "一致性校验通过：violations=0"
