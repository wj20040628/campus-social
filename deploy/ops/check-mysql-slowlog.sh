#!/usr/bin/env bash
# MySQL 慢查询日志状态核对（2026-11-14）
#
# 用途：判断线上的慢查询日志到底开没开，避免文档写「已开/已归零」、
# 实际没开，排查性能时被误导。只读 SHOW GLOBAL VARIABLES，不改配置。
#
# 用法：
#   bash check-mysql-slowlog.sh
# 默认读取 /opt/campus/.env 的 MYSQL_USER / MYSQL_PASSWORD / MYSQL_HOST / MYSQL_PORT。
#
# 退出码：
#   0 = 已开启（slow_query_log=ON）
#   2 = 未开启
#   1 = 检查本身失败（缺 env、连不上、找不到 mysql 客户端）
set -uo pipefail

BASE="${BASE:-/opt/campus}"
[ -f "$BASE/.env" ] || { echo "错误：缺少 $BASE/.env" >&2; exit 1; }
set -a
# shellcheck disable=SC1091
. "$BASE/.env"
set +a
: "${MYSQL_USER:?/opt/campus/.env 缺少 MYSQL_USER}"
: "${MYSQL_PASSWORD:?/opt/campus/.env 缺少 MYSQL_PASSWORD}"
export MYSQL_PWD="$MYSQL_PASSWORD"

MYSQL_BIN="$(command -v mysql || true)"
if [ -z "$MYSQL_BIN" ]; then
  for candidate in /www/server/mysql/bin/mysql /usr/local/mysql/bin/mysql /usr/bin/mysql; do
    if [ -x "$candidate" ]; then
      MYSQL_BIN="$candidate"
      break
    fi
  done
fi
[ -n "$MYSQL_BIN" ] || { echo "错误：找不到 mysql 客户端（宝塔安装在 /www/server/mysql/bin/mysql，系统包在 /usr/bin/mysql）" >&2; exit 1; }

DB_HOST="${MYSQL_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-3306}"

ROWS="$("$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" -N -B -e "
SHOW GLOBAL VARIABLES WHERE Variable_name IN (
  'slow_query_log','slow_query_log_file','log_output','long_query_time',
  'log_queries_not_using_indexes','min_examined_row_limit'
);")" || { echo "错误：连不上 MySQL（$DB_HOST:$DB_PORT），先确认服务和账号" >&2; exit 1; }

value_of() { printf '%s\n' "$ROWS" | awk -v key="$1" '$1 == key { print $2; exit }'; }

SLOW_LOG="$(value_of slow_query_log)"
LOG_FILE="$(value_of slow_query_log_file)"
LOG_OUTPUT="$(value_of log_output)"
LONG_TIME="$(value_of long_query_time)"
NOT_INDEXED="$(value_of log_queries_not_using_indexes)"
MIN_ROWS="$(value_of min_examined_row_limit)"

if [ "$SLOW_LOG" = "ON" ]; then
  echo "慢查询日志：已开启"
  echo "  阈值 long_query_time=${LONG_TIME:-?}s（推荐 1）"
  echo "  输出方式 log_output=${LOG_OUTPUT:-?}（FILE=写文件，TABLE=写 mysql.slow_log 表）"
  echo "  未走索引记录=${NOT_INDEXED:-?}（推荐 OFF）"
  echo "  最小扫描行数=${MIN_ROWS:-?}（推荐 100）"
  case "$LOG_OUTPUT" in
    *TABLE*) echo "  记录位置：mysql.slow_log 表（不看日志文件）" ;;
    *) echo "  日志 slow_query_log_file=${LOG_FILE:-?}" ;;
  esac
  # 只开了但不按文档阈值配，等于抓不到 1–10 秒的慢查询，必须提示
  if [ -n "$LONG_TIME" ] && awk -v t="$LONG_TIME" 'BEGIN { exit !(t > 1) }'; then
    echo "提示：阈值 ${LONG_TIME}s 高于文档约定的 1s，1–${LONG_TIME%.*} 秒的慢查询不会被记录。" >&2
  fi
  case "$LOG_OUTPUT" in
    *TABLE*) ;;
    *)
      case "$LOG_FILE" in
        /*)
          if [ ! -f "$LOG_FILE" ]; then
            echo "提示：日志文件还不存在，通常是开启后还没有慢查询；持续为空也属正常。" >&2
          fi
          ;;
        *)
          echo "提示：slow_query_log_file 是相对路径（${LOG_FILE:-?}），实际位置取决于 MySQL 数据目录。" >&2
          ;;
      esac
      ;;
  esac
  PERSIST_ROWS="$("$MYSQL_BIN" -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" -N -B \
    -e "SELECT CONCAT(VARIABLE_NAME,'=',VARIABLE_VALUE) FROM performance_schema.persisted_variables WHERE VARIABLE_NAME IN ('slow_query_log','long_query_time','log_queries_not_using_indexes','min_examined_row_limit');" \
    2>&1)"
  PERSIST_RC=$?
  if [ "$PERSIST_RC" -ne 0 ]; then
    echo "  持久化：查不到 performance_schema.persisted_variables（$(printf '%s\n' "$PERSIST_ROWS" | sed -n '1p')）；当前账号可能无读权限，请在宝塔「终端」用 root 复查" >&2
  else
    PERSISTED="$(printf '%s\n' "$PERSIST_ROWS" | awk -F= '$1 == "slow_query_log" { print $2; exit }')"
    PERSIST_COUNT="$(printf '%s\n' "$PERSIST_ROWS" | awk 'NF' | wc -l | tr -d ' ')"
    case "$PERSISTED" in
      ON) echo "  持久化：已持久化（SET PERSIST，共 ${PERSIST_COUNT} 项），MySQL 重启后仍为 ON" ;;
      '') echo "  持久化：未读到持久化项；若重启后变 OFF，按文档 §3.1 方案 B 执行 SET PERSIST" ;;
      *) echo "  持久化：slow_query_log=${PERSISTED}" ;;
    esac
  fi
  exit 0
fi

echo "慢查询日志：未开启（slow_query_log=${SLOW_LOG:-unknown}）"
echo "影响：没有慢 SQL 数据，性能排查只能看 /actuator/prometheus 的应用指标。"
echo "开启步骤：docs/ops/performance-metrics.md §3.1（终端 SET GLOBAL 临时开启，SET PERSIST 持久化）"
exit 2