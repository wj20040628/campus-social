#!/usr/bin/env bash
# 友声 1.13.3 部署脚本（2026-10-31）
# 用法：
#   1) 把 ys_deploy_20261031_v1133.zip 上传到 /opt/campus/incoming
#   2) cd /opt/campus/incoming && unzip -o ys_deploy_20261031_v1133.zip
#   3) 进入解压目录（脚本所在目录），执行：bash deploy.sh
# 脚本先做文件校验、数据库备份与环境预检，失败不会改动线上。
set -euo pipefail

VERSION="1.13.3"
JAR_MD5="2d9111c9a0b06c0ebb78c80958b2f02e"
FRONT_ENTRY="assets/index-gydVwA6_.js"
FRONT_SHA256="b891743fae7994bdf599ed8fc7a4eb9502a4ed0c0182424a2db2fad7a954873d"
FRONT_BYTES="333827"
DB_NAME="campus_social_v2"
BASE="/opt/campus"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="${PKG_DIR:-$SCRIPT_DIR}"

JAR_SRC="$PKG_DIR/campus-social.jar"
WEB_SRC="$PKG_DIR/web"
SQL_DIR="$PKG_DIR/sql"
MANIFEST="$PKG_DIR/release-manifest.json"

TS="$(date +%Y%m%d_%H%M%S)"
BACKUP_JAR="$BASE/backup/campus-social.jar.$TS"
BACKUP_WEB="$BASE/web.bak_$TS"
BACKUP_VERSION="$BASE/backup/VERSION.$TS"
ROLLBACK=0

log() { printf '%s\n' "$*"; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

rollback() {
  log "!! 部署失败，回滚到部署前状态 ..."
  systemctl stop campus || true
  if [ -f "$BACKUP_JAR" ]; then cp -f "$BACKUP_JAR" "$BASE/campus-social.jar"; fi
  if [ -d "$BACKUP_WEB" ]; then
    rm -rf "$BASE/web"
    mv "$BACKUP_WEB" "$BASE/web"
  fi
  if [ -f "$BACKUP_VERSION" ]; then
    cp -f "$BACKUP_VERSION" "$BASE/VERSION"
  else
    rm -f "$BASE/VERSION"
  fi
  systemctl start campus || true
  sleep 5
  curl -s --max-time 3 127.0.0.1:8080/actuator/health || true
  echo
  log "备份仍保留：$BACKUP_JAR  $BACKUP_WEB"
  log "注意：SQL 迁移为幂等注释修复，不会自动反向回滚；数据库备份见 $BASE/backup/"
}
# 用 EXIT trap 而不是 ERR：die 走的是 exit，ERR trap 不会触发；
# 只有 EXIT 能在健康检查失败等显式退出路径上保证回滚。
cleanup() {
  local rc=$?
  if [ "$ROLLBACK" = "1" ] && [ "$rc" -ne 0 ]; then
    set +e
    rollback
  fi
}
trap cleanup EXIT

[ "$(id -u)" = "0" ] || die "请用 root 执行"
[ "$PKG_DIR" != "$BASE" ] || die "不要把部署包解压到 /opt/campus 根目录"
[ -d "$BASE" ] || die "$BASE 不存在"

log "== 1/8 校验部署包 =="
[ -f "$JAR_SRC" ] || die "缺少 $JAR_SRC"
[ -f "$WEB_SRC/index.html" ] || die "缺少 $WEB_SRC/index.html"
[ -f "$MANIFEST" ] || die "缺少 $MANIFEST"
[ -f "$SQL_DIR/migration_2026-10-30_drop_buddy_tables.sql" ] || die "缺少 buddy 表下线迁移"
[ -f "$SQL_DIR/migration_2026-10-31_fix_comment_encoding.sql" ] || die "缺少注释编码修复迁移"

echo "$JAR_MD5  $JAR_SRC" | md5sum -c - >/dev/null || die "jar MD5 不匹配"
grep -q "$FRONT_ENTRY" "$WEB_SRC/index.html" || die "前端入口不是 $FRONT_ENTRY"
ENTRY_FILE="$WEB_SRC/$FRONT_ENTRY"
[ -f "$ENTRY_FILE" ] || die "缺少前端入口文件 $FRONT_ENTRY"
[ "$(sha256sum "$ENTRY_FILE" | awk '{print $1}')" = "$FRONT_SHA256" ] || die "前端入口 SHA256 不匹配"
[ "$(stat -c '%s' "$ENTRY_FILE")" = "$FRONT_BYTES" ] || die "前端入口大小不匹配"
grep -q '"version": "1.13.3"' "$MANIFEST" || die "release-manifest.json 版本不是 1.13.3"
log "文件校验通过：jar $JAR_MD5 / 入口 $FRONT_ENTRY"

log "== 2/8 环境预检 =="
[ -f "$BASE/.env" ] || die "缺少 $BASE/.env"
[ -f "$BASE/backup.sh" ] || die "缺少 $BASE/backup.sh，无法先做数据库备份"
set -a
# shellcheck disable=SC1091
. "$BASE/.env"
set +a
: "${MYSQL_USER:?/opt/campus/.env 缺少 MYSQL_USER}"
: "${MYSQL_PASSWORD:?/opt/campus/.env 缺少 MYSQL_PASSWORD}"
export MYSQL_PWD="$MYSQL_PASSWORD"
DB_HOST="${MYSQL_HOST:-127.0.0.1}"
DB_PORT="${MYSQL_PORT:-3306}"
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" --default-character-set=utf8mb4 \
  -e "SELECT 1" "$DB_NAME" >/dev/null || die "数据库连接失败"
systemctl is-active campus >/dev/null || die "campus 当前未运行，先排查再部署"
log "数据库连接与 campus 服务正常"

log "== 3/8 备份数据库（复用每日备份脚本，含 OSS 异地留存）=="
PREV_SUCCESS=0
if [ -f "$BASE/backup/.last_success" ]; then
  PREV_SUCCESS="$(stat -c '%Y' "$BASE/backup/.last_success")"
fi
bash "$BASE/backup.sh" || die "数据库备份失败，已停止部署（线上未改动）"
NEW_SUCCESS="$(stat -c '%Y' "$BASE/backup/.last_success" 2>/dev/null || echo 0)"
[ "$NEW_SUCCESS" -gt "$PREV_SUCCESS" ] || die "备份未产生新的成功标记，已停止部署"
log "数据库备份成功：$(tail -n 1 "$BASE/backup/.last_success")"

log "== 4/8 备份当前 jar / web / VERSION =="
mkdir -p "$BASE/backup"
cp -f "$BASE/campus-social.jar" "$BACKUP_JAR"
if [ -d "$BASE/web" ]; then mv "$BASE/web" "$BACKUP_WEB"; fi
if [ -f "$BASE/VERSION" ]; then cp -f "$BASE/VERSION" "$BACKUP_VERSION"; fi
log "已备份：$BACKUP_JAR ${BACKUP_WEB:-（无旧 web）}"

log "== 5/8 停服务并落位 =="
ROLLBACK=1
systemctl stop campus
cp -f "$JAR_SRC" "$BASE/campus-social.jar"
mkdir -p "$BASE/web"
cp -a "$WEB_SRC/." "$BASE/web/"
printf '%s\n' "$VERSION" > "$BASE/VERSION"

log "== 6/8 执行数据库迁移（幂等）=="
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" --default-character-set=utf8mb4 "$DB_NAME" \
  < "$SQL_DIR/migration_2026-10-30_drop_buddy_tables.sql"
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$MYSQL_USER" --default-character-set=utf8mb4 "$DB_NAME" \
  < "$SQL_DIR/migration_2026-10-31_fix_comment_encoding.sql"
log "迁移执行完成"

log "== 7/8 启动并探活（最多等 120 秒）=="
systemctl start campus
OK=0
for _ in $(seq 1 40); do
  sleep 3
  if curl -fsS --max-time 3 127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    OK=1
    break
  fi
done
[ "$OK" = "1" ] || die "健康检查未通过"
ROLLBACK=0
trap - ERR
log "本机健康检查通过"

log "== 8/8 部署结果 =="
echo -n "版本文件："; cat "$BASE/VERSION"
echo -n "本机健康："; curl -s --max-time 3 127.0.0.1:8080/actuator/health; echo
echo -n "公网健康："; curl -s --max-time 6 https://yousheng8.cn/healthz || true; echo
echo -n "前端入口："; grep -o 'assets/index-[^"]*\.js' "$BASE/web/index.html" | head -1
echo "启动日志："
tail -n 80 "$BASE/app.log" | grep -E "Started CampusSocialApplication|ERROR" || true
echo "服务状态：$(systemctl is-active campus)"
echo "备份后缀：$TS"
echo "回滚：cp $BACKUP_JAR $BASE/campus-social.jar && rm -rf $BASE/web && mv $BACKUP_WEB $BASE/web && systemctl restart campus"

rm -f "$JAR_SRC"
rm -rf "$WEB_SRC"
log "部署完成；部署包内的 jar 与 web 已清理。"
