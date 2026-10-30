#!/usr/bin/env bash
# 友声 1.13.2 部署脚本（2026-10-30）
# 用法：
#   1) 把 ys_deploy_20261030_v1132.zip 上传到服务器临时目录（推荐 /opt/campus/incoming）
#   2) 在该目录执行：unzip -o ys_deploy_20261030_v1132.zip && bash deploy.sh
#   脚本自动定位解压目录；不要把包解压到 /opt/campus 根目录。
set -euo pipefail

cd /opt/campus

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="${PKG_DIR:-$SCRIPT_DIR}"

if [ "$PKG_DIR" = "/opt/campus" ]; then
  echo "请不要把部署包解压到 /opt/campus 根目录；新建一个临时目录（如 /opt/campus/incoming）再执行。"
  exit 1
fi

JAR_MD5="985a19b9472cd908d8033acfc57d800c"
JAR_SRC="$PKG_DIR/campus-social.jar"
WEB_SRC="$PKG_DIR/web"
TS="$(date +%Y%m%d_%H%M%S)"
BACKUP_JAR="backup/campus-social.jar.$TS"
BACKUP_WEB="web.bak_$TS"
ROLLBACK=0

rollback() {
  echo "!! 部署失败，开始回滚到部署前状态 ..."
  systemctl stop campus || true
  if [ -f "$BACKUP_JAR" ]; then cp -f "$BACKUP_JAR" campus-social.jar; fi
  if [ -d "$BACKUP_WEB" ]; then rm -rf web; mv "$BACKUP_WEB" web; fi
  systemctl start campus || true
  sleep 5
  curl -s --max-time 3 127.0.0.1:8080/actuator/health || true
  echo
  echo "备份仍保留：$BACKUP_JAR  $BACKUP_WEB"
}
trap 'if [ "$ROLLBACK" = "1" ]; then rollback; fi' ERR

echo "== 1/6 校验上传文件 =="
[ -f "$JAR_SRC" ] || { echo "缺少 $JAR_SRC"; exit 1; }
[ -f "$WEB_SRC/index.html" ] || { echo "缺少 $WEB_SRC/index.html"; exit 1; }
echo "$JAR_MD5  $JAR_SRC" | md5sum -c -
grep -q 'index-kJtT8_w_.js' "$WEB_SRC/index.html" || { echo "前端入口不是 index-kJtT8_w_.js，上传包可能不完整"; exit 1; }
echo "文件校验通过"

echo "== 2/6 备份当前版本 =="
mkdir -p backup
cp campus-social.jar "$BACKUP_JAR"
echo "jar 已备份到 $BACKUP_JAR"

echo "== 3/6 停服务并替换 jar =="
ROLLBACK=1
systemctl stop campus
cp "$JAR_SRC" campus-social.jar

echo "== 4/6 替换前端 =="
mv web "$BACKUP_WEB"
cp -a "$WEB_SRC" web

echo "== 5/6 启动并探活（最多等 120 秒）=="
systemctl start campus
OK=0
for i in $(seq 1 40); do
  sleep 3
  if curl -fsS --max-time 3 127.0.0.1:8080/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then OK=1; break; fi
done
if [ "$OK" != "1" ]; then echo "健康检查未通过"; false; fi
ROLLBACK=0
trap - ERR

echo "== 6/6 部署成功 =="
echo "备份后缀：$TS"
echo -n "本机健康："; curl -s --max-time 3 127.0.0.1:8080/actuator/health; echo
echo -n "公网健康："; curl -s --max-time 5 https://yousheng8.cn/healthz || true; echo
echo -n "前端入口："; grep -o 'assets/index-[^"]*\.js' web/index.html | head -1
echo "启动日志："
tail -n 60 app.log | grep -E "Started CampusSocialApplication|ERROR" || true
echo "服务状态：$(systemctl is-active campus)"
rm -f "$JAR_SRC"
rm -rf "$WEB_SRC"
echo "部署包内的 jar 与 web 已清理（zip 与 deploy.sh 保留，可手动删除）。"
