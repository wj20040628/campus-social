#!/usr/bin/env bash
# 友声 Nginx 过载保护配置应用脚本（2026-11-13）
#
# 用法：bash apply-nginx.sh
# 可选环境变量（默认值即生产路径）：BASE / CONF_SRC / CONF_DST / SITE_HOST
#
# 流程：应用前探活 -> 备份线上配置 -> 落位 -> nginx -t -> reload -> 复探活。
# 任一步失败自动恢复备份并用非零码退出；备份是普通文件（已解引用符号链接），
# 线上 sites-enabled/default 若是符号链接，写入会穿透到 sites-available，链接本身保留。
set -euo pipefail

BASE="${BASE:-/opt/campus}"
CONF_SRC="${CONF_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/production.conf}"
CONF_DST="${CONF_DST:-/etc/nginx/sites-enabled/default}"
SITE_HOST="${SITE_HOST:-yousheng8.cn}"

TS="$(date +%Y%m%d_%H%M%S)"
BACKUP_DIR="$BASE/backup"
BACKUP="$BACKUP_DIR/nginx-default_$TS.conf"

log() { printf '%s\n' "$*"; }
die() { printf '错误：%s\n' "$*" >&2; exit 1; }

probe() {
  curl -fsSk --max-time 5 --resolve "$SITE_HOST:443:127.0.0.1" "https://$SITE_HOST/healthz" >/dev/null
}

rollback() {
  cp -p "$BACKUP" "$CONF_DST"
  systemctl reload nginx >/dev/null 2>&1 || true
  printf '已回滚到应用前配置：%s\n' "$BACKUP" >&2
}

[ "$(id -u)" = "0" ] || die "请用 root 执行"
[ -f "$CONF_SRC" ] || die "找不到新配置：$CONF_SRC"
[ -f "$CONF_DST" ] || die "找不到线上配置：$CONF_DST"
command -v nginx >/dev/null 2>&1 || die "未安装 nginx"
command -v systemctl >/dev/null 2>&1 || die "没有 systemctl"
command -v curl >/dev/null 2>&1 || die "没有 curl"

probe || die "应用前健康检查已失败，本次不动配置（先排查线上服务）"

# 漂移保护：本脚本是「整文件覆盖」，若线上配置已不是模板对应的形态（手工加过 location、
# 换过证书路径等），覆盖会静默丢掉现场改动。缺任一锚点就停手，先人工 diff。
for anchor in "listen 443 ssl http2;" "location = /healthz {" "location /assets/ {" "location /ws/ {"; do
  grep -Fq "$anchor" "$CONF_DST" || die "线上配置缺少锚点「$anchor」，与模板差异过大；请先 diff $CONF_DST $CONF_SRC 人工确认"
done

log "本次改动预览（旧 -> 新，仅显示前 60 行）："
diff -u "$CONF_DST" "$CONF_SRC" | head -60 || true

mkdir -p "$BACKUP_DIR"
cp -Lp "$CONF_DST" "$BACKUP"
log "已备份线上配置：$BACKUP"

cat "$CONF_SRC" > "$CONF_DST"
if ! nginx -t; then
  rollback
  die "新配置未通过 nginx -t，未生效"
fi

if ! systemctl reload nginx; then
  rollback
  die "nginx reload 失败，已回滚"
fi

sleep 1
if ! probe; then
  rollback
  die "reload 后健康检查失败，已回滚"
fi

log "过载保护配置已生效：全站限流 300r/s（burst 600）、连接上限 1600、静态文件句柄缓存。"
log "回滚命令：cp -p $BACKUP $CONF_DST && systemctl reload nginx"
